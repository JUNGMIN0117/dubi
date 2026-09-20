package com.ssafy.ddubi.domain.place.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ssafy.ddubi.domain.place.dto.response.PlaceSearchItem;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** PLACE-001 카카오 로컬 검색 프록시 — 요청 파라미터 변환과 응답 매핑을 고정한다. */
class KakaoLocalClientTest {

    private static final String BASE_URL = "https://dapi.test";

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private KakaoLocalClient client(String key) {
        return new KakaoLocalClient(builder, BASE_URL, key);
    }

    @Test
    @DisplayName("0-base page를 바꾸고 수도권·부산 rect 결과를 합친다")
    void translatesPagingAndRect() {
        KakaoLocalClient client = client("local-key");
        server.expect(
                        requestToUriTemplate(
                                BASE_URL
                                        + "/v2/local/search/keyword.json?query={q}&page={p}&size={s}&rect={r}",
                                "강남역",
                                1,
                                15,
                                "125.8,36.85,127.9,38.3"))
                .andExpect(header("Authorization", "KakaoAK local-key"))
                .andRespond(
                        withSuccess(
                                """
                        {"documents":[{"id":"123","place_name":"강남역 2호선",
                          "road_address_name":"서울 강남구 강남대로 396","address_name":"역삼동",
                          "x":"127.02761","y":"37.49795"}],
                         "meta":{"is_end":false,"pageable_count":45,"total_count":1200}}
                        """,
                                MediaType.APPLICATION_JSON));
        server.expect(
                        requestToUriTemplate(
                                BASE_URL
                                        + "/v2/local/search/keyword.json?query={q}&page={p}&size={s}&rect={r}",
                                "강남역",
                                1,
                                15,
                                "128.7,34.8,129.4,35.45"))
                .andExpect(header("Authorization", "KakaoAK local-key"))
                .andRespond(
                        withSuccess(
                                """
                        {"documents":[{"id":"456","place_name":"부산 강남역 버스정류장",
                          "road_address_name":"부산광역시","address_name":"부산광역시",
                          "x":"129.01","y":"35.17"}],
                         "meta":{"is_end":true,"pageable_count":1,"total_count":1}}
                        """,
                                MediaType.APPLICATION_JSON));

        // 명세서 기본 size=20 은 카카오 상한 15 로 잘린다.
        PageResponse<PlaceSearchItem> result = client.search("강남역", 0, 20);

        assertThat(result.content()).hasSize(2);
        PlaceSearchItem item = result.content().get(0);
        assertThat(item.providerPlaceId()).isEqualTo("123");
        assertThat(item.name()).isEqualTo("강남역 2호선");
        assertThat(item.address()).isEqualTo("서울 강남구 강남대로 396");
        assertThat(item.lat()).isEqualByComparingTo(new BigDecimal("37.49795"));
        assertThat(item.lng()).isEqualByComparingTo(new BigDecimal("127.02761"));
        assertThat(result.hasNext()).isTrue();
        assertThat(result.totalElements()).isEqualTo(46);
        server.verify();
    }

    @Test
    @DisplayName("도로명 주소가 없으면 지번 주소로 대신한다")
    void fallsBackToLotAddress() {
        KakaoLocalClient client = client("local-key");
        server.expect(
                        requestToUriTemplate(
                                BASE_URL
                                        + "/v2/local/search/keyword.json?query={q}&page={p}&size={s}&rect={r}",
                                "서울숲",
                                1,
                                15,
                                "125.8,36.85,127.9,38.3"))
                .andRespond(
                        withSuccess(
                                """
                        {"documents":[{"id":"9","place_name":"서울숲",
                          "road_address_name":"","address_name":"성동구 성수동1가",
                          "x":"127.0374","y":"37.5443"}],
                         "meta":{"is_end":true,"pageable_count":1,"total_count":1}}
                        """,
                                MediaType.APPLICATION_JSON));
        server.expect(
                        requestToUriTemplate(
                                BASE_URL
                                        + "/v2/local/search/keyword.json?query={q}&page={p}&size={s}&rect={r}",
                                "서울숲",
                                1,
                                15,
                                "128.7,34.8,129.4,35.45"))
                .andRespond(
                        withSuccess(
                                """
                        {"documents":[],"meta":{"is_end":true,"pageable_count":0,"total_count":0}}
                        """,
                                MediaType.APPLICATION_JSON));

        assertThat(client.search("서울숲", 0, 15).content().get(0).address()).isEqualTo("성동구 성수동1가");
    }

    @Test
    @DisplayName("REST API 키가 없으면 카카오를 호출하지 않고 500 으로 끊는다")
    void missingKeyFailsFast() {
        KakaoLocalClient client = client("");

        assertThatThrownBy(() -> client.search("강남역", 0, 15))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        server.verify();
    }
}
