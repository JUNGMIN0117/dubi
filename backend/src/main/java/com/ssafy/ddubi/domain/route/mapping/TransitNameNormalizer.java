package com.ssafy.ddubi.domain.route.mapping;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 외부 제공자와 공식 마스터의 표기 차이를 후보 검색용 문자열로 정규화한다.
 *
 * <p>정규화는 후보를 좁히는 수단일 뿐 같은 공식 ID라는 증명이 아니다. 최종 확정은
 * {@link BusSegmentMatcher}와 {@link SubwaySegmentMatcher}가 노선, 승·하차 순서,
 * 중간 정류장·역과 방향을 다시 검사한다.</p>
 *
 * <p>자동 규칙으로 해결할 수 없는 표기 차이는 검증된 별칭만 생성자에 주입한다.
 * 예를 들어 {@code 9003 -> 9003성남}, {@code 서울역 -> 서울}처럼 방향이 명확한 별칭만 허용한다.</p>
 */
public class TransitNameNormalizer {

    private final Map<String, String> routeAliases;
    private final Map<String, String> stopAliases;

    public TransitNameNormalizer() {
        this(Map.of(), Map.of());
    }

    /** 별칭은 검증된 외부 표기 → 공식 표기 방향으로만 주입한다. */
    public TransitNameNormalizer(Map<String, String> routeAliases, Map<String, String> stopAliases) {
        this.routeAliases = normalizeAliases(routeAliases, true);
        this.stopAliases = normalizeAliases(stopAliases, false);
    }

    public String normalizeRouteName(String value) {
        // 공식 노선명과 외부 노선명 모두 같은 기본 규칙을 거친 뒤 검증된 별칭을 적용한다.
        String normalized = normalizeRouteBase(value);
        return routeAliases.getOrDefault(normalized, normalized);
    }

    public String normalizeStopName(String value) {
        // '역'은 실제 역명의 일부이므로 제거하지 않고, 의미 없는 '정류장' 접미사만 정리한다.
        String normalized = normalizeStopBase(value);
        return stopAliases.getOrDefault(normalized, normalized);
    }

    /**
     * 지하철 호선명을 비교 가능한 형태로 만든다.
     * 카카오의 {@code 수도권 02호선}과 공식 마스터의 {@code 2호선}처럼 제공자 장식어와
     * 숫자 앞의 0만 다른 경우를 같은 후보로 찾는다. 경의중앙선 같은 고유 노선명은 보존한다.
     */
    public String normalizeSubwayLineName(String value) {
        String normalized = normalizeRouteName(value);
        for (String prefix : new String[]{"수도권전철", "서울지하철", "지하철", "수도권"}) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length());
                break;
            }
        }
        if (normalized.matches("0+[0-9]+호선")) {
            normalized = normalized.replaceFirst("^0+", "");
        }
        return normalized;
    }

    /**
     * 지하철 역명을 비교 가능한 형태로 만든다.
     * 공식 데이터의 {@code 강남}과 카카오의 {@code 강남역}을 연결하기 위해 마지막 '역'만 제거한다.
     * {@code 역촌}처럼 이름 앞이나 중간에 있는 글자는 제거하지 않는다.
     */
    public String normalizeSubwayStationName(String value) {
        String normalized = normalizeStopName(value);
        if (normalized.endsWith("역")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeRouteBase(String value) {
        String normalized = normalizeCommon(value, true);
        // '422번', '422버스'처럼 제공자별 장식어만 제거한다. 실제 노선 번호 본문은 보존한다.
        if (normalized.endsWith("버스")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        if (normalized.endsWith("번")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeStopBase(String value) {
        String normalized = normalizeCommon(value, false);
        if (normalized.endsWith("정류장")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    private Map<String, String> normalizeAliases(Map<String, String> aliases, boolean route) {
        if (aliases == null || aliases.isEmpty()) {
            return Map.of();
        }
        // 별칭 양쪽도 먼저 정규화해야 호출 시점의 문자열과 동일한 키 공간에서 조회할 수 있다.
        return aliases.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                entry -> route ? normalizeRouteBase(entry.getKey()) : normalizeStopBase(entry.getKey()),
                entry -> route ? normalizeRouteBase(entry.getValue()) : normalizeStopBase(entry.getValue())
        ));
    }

    private String normalizeCommon(String value, boolean keepHyphen) {
        // NFKC로 전각 숫자·문자 등을 통일한 뒤 괄호 부가설명과 검색에 불필요한 구두점을 제거한다.
        String source = Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
                .replaceAll("\\([^)]*\\)", "")
                .replaceAll("\\[[^]]*]", "")
                .toUpperCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(source.length());
        source.codePoints().forEach(codePoint -> {
            // 버스 노선의 119-1과 1191은 다른 노선이므로 노선명에서는 하이픈을 반드시 보존한다.
            if (Character.isLetterOrDigit(codePoint) || (keepHyphen && codePoint == '-')) {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }
}
