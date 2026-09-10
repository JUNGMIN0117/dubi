package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 공식 시간표를 운행일별 절대시각으로 정규화한 artifact를 시작 시 한 번 읽는다.
 * DB에 쓰지 않으며 열차번호와 실시간 ID의 대응은 수집팀이 검증한 버전만 받는다.
 * 자정 이후 운행을 다음 날짜로 명시하여 휴일·막차를 임의 배차 간격으로 복원하지 않는다.
 * 미설정/손상은 명시적 unavailable이다. 테스트 시간표를 운영 기본값으로 사용하지 않는다.
 */
@Component
public class SubwayTimetable {
    /** static stationId와 실시간 providerStationId는 별개다. 통과역도 넣되 stops=false로 표시한다. */
    public record Call(String stationId, String providerStationId, Instant arrivalAt, Instant departureAt, boolean stops) { }
    /**
     * 한 운행일의 단방향 운행이다. trainId는 해당 제공자의 운행 열차 ID이며 편성 고유번호가 아니다.
     * direction은 canonical 방향, arrivalDirection/positionDirection은 각 API 원문 코드다.
     * arrivalDestination/positionDestination은 별도 검증한 원문 행선지다. 이름만으로 ID를 생성하지 않는다.
     */
    public record Trip(String key, String lineId, String providerLineId, String direction, String trainId,
                       String arrivalDirection, String positionDirection, String arrivalDestination,
                       String positionDestination, String trainType, LocalDate serviceDate, List<Call> calls) {
        public Trip { calls = List.copyOf(calls); }
    }
    /** publishedAt은 확보 시각이다. validFrom/Until은 운행 적용 범위로 끝 시각을 포함하지 않는다. */
    public record Data(String version, String masterDataVersion, String source, Instant publishedAt,
                       Instant validFrom, Instant validUntil, List<Trip> trips) {
        public Data { trips = List.copyOf(trips); }
    }
    private final Data data;
    private final String unavailableReason;

    /** 경로는 서버 설정 전용이다. 32 MiB 초과 파일과 불완전한 운행표는 전체 거절하여 부분 적재를 숨기지 않는다. */
    @org.springframework.beans.factory.annotation.Autowired
    public SubwayTimetable(ObjectMapper json, @Value("${ddubi.subway-timing.timetable-path:}") String path) {
        Data loaded = null;
        String reason = "SUBWAY_TIMETABLE_NOT_CONFIGURED";
        if (path != null && !path.isBlank()) {
            try {
                Path file = Path.of(path);
                if (Files.size(file) > 32L * 1024 * 1024) throw new IllegalArgumentException();
                loaded = validate(json.readValue(Files.readString(file), Data.class));
                reason = null;
            } catch (Exception invalid) { loaded = null; reason = "SUBWAY_TIMETABLE_INVALID"; }
        }
        data = loaded; unavailableReason = reason;
    }

    /** 고정 증거 Replay와 테스트용 입력도 운영과 같은 검증을 거친다. */
    SubwayTimetable(Data data) { this.data = validate(data); this.unavailableReason = null; }
    public Data data() { return data; }
    public String unavailableReason() { return unavailableReason; }

    private static Data validate(Data d) {
        if (d == null || blank(d.version()) || blank(d.masterDataVersion()) || blank(d.source())
                || d.publishedAt() == null || d.validFrom() == null || d.validUntil() == null
                || !d.validFrom().isBefore(d.validUntil()) || d.trips().isEmpty()) throw new IllegalArgumentException();
        var keys = new HashSet<String>();
        var identities = new HashSet<String>();
        for (Trip t : d.trips()) {
            if (blank(t.key()) || !keys.add(t.key()) || blank(t.lineId()) || !id(t.providerLineId())
                    || blank(t.direction()) || !id(t.trainId()) || blank(t.arrivalDirection())
                    || blank(t.positionDirection()) || blank(t.arrivalDestination()) || blank(t.positionDestination())
                    || blank(t.trainType()) || t.serviceDate() == null || t.calls().size() < 2
                    || !identities.add(t.serviceDate() + ":" + t.providerLineId() + ":" + t.trainId())) throw new IllegalArgumentException();
            var stations = new HashSet<String>();
            var providerStations = new HashSet<String>();
            Instant previous = null;
            Instant day = t.serviceDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
            for (Call c : t.calls()) {
                // 순환 운행에서 동일 역을 재방문하는 경우 위치만으로 회차를 구별할 수 없다. 분리된 운행표를 요구한다.
                if (blank(c.stationId()) || !stations.add(c.stationId()) || !id(c.providerStationId())
                        || !providerStations.add(c.providerStationId()) || c.arrivalAt() == null || c.departureAt() == null
                        || c.departureAt().isBefore(c.arrivalAt()) || (previous != null && !c.arrivalAt().isAfter(previous))
                        || c.arrivalAt().isBefore(d.validFrom()) || !c.departureAt().isBefore(d.validUntil())
                        || c.arrivalAt().isBefore(day) || !c.departureAt().isBefore(day.plus(Duration.ofHours(30)))) throw new IllegalArgumentException();
                previous = c.departureAt();
            }
        }
        return d;
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean id(String s) { return s != null && s.matches("[0-9A-Za-z_-]{1,60}"); }
}
