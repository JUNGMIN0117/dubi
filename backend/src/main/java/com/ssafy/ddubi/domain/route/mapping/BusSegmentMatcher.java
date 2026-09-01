package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.Coordinate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 카카오의 이름 기반 BUS step을 공식 노선과 인접 정류장 구간으로 변환한다.
 *
 * <p>처리 단계는 다음과 같다.</p>
 * <ol>
 *     <li>카카오 vehicles.name을 정규화해 공식 노선 후보를 찾는다.</li>
 *     <li>후보 노선마다 승차 정류장보다 뒤에 있는 하차 정류장 조합을 만든다.</li>
 *     <li>카카오 중간 정류장이 공식 목록에서 같은 순서로 등장하는지 비율을 계산한다.</li>
 *     <li>좌표가 있으면 카카오 path가 승차→하차 방향으로 진행하는지 교차 검증한다.</li>
 *     <li>최상위 후보가 하나이고 임계값을 통과할 때만 EXACT로 확정한다.</li>
 *     <li>확정한 승차~하차 범위를 실제 인접 정류장 구간 목록으로 전개한다.</li>
 * </ol>
 *
 * <p>이 클래스는 저장소, Spring, 카카오 DTO, segment_id 문자열 형식에 의존하지 않는다.
 * 파일 또는 DB에서 읽은 {@link BusRouteMaster}를 주입하면 같은 입력에 항상 같은 결과를 반환한다.</p>
 */
public class BusSegmentMatcher {

    // 현재 PoC에서 합의한 자동 확정 기준이다. 변경 영향이 한곳에 모이도록 상수로 격리한다.
    private static final double EXACT_SCORE = 0.90;
    private static final double EXACT_PASSING_RATIO = 0.80;
    // 부동소수점 오차와 미세한 점수 차이로 동률 후보를 임의 선택하지 않게 하는 비교 허용치다.
    private static final double TIE_EPSILON = 0.01;

    private final TransitNameNormalizer normalizer;
    private final Map<String, List<BusRouteMaster>> routesByNormalizedName;

    public BusSegmentMatcher(Collection<BusRouteMaster> routes) {
        this(routes, new TransitNameNormalizer());
    }

    public BusSegmentMatcher(Collection<BusRouteMaster> routes, TransitNameNormalizer normalizer) {
        this.normalizer = Objects.requireNonNull(normalizer);
        // 요청마다 전체 마스터를 순회하지 않도록 생성 시점에 정규화 노선명 인덱스를 만든다.
        this.routesByNormalizedName = indexRoutes(routes == null ? List.of() : routes);
    }

    /**
     * 카카오 BUS step 하나를 공식 마스터에 매핑한다.
     * EXACT 외 상태에서는 잘못된 실시간 보정을 막기 위해 인접 구간 목록을 비워 반환한다.
     */
    public BusMappingResult match(BusMappingRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // 1단계: 한 step에 여러 vehicles가 올 수 있으므로 모든 노선명을 공식 후보로 확장한다.
        List<BusRouteMaster> routeCandidates = findRouteCandidates(request.providerRouteNames());
        if (routeCandidates.isEmpty()) {
            return failed(BusMappingStatus.UNMAPPED, "ROUTE_NOT_FOUND", 0);
        }

        // 2단계: 같은 이름의 정류장이 노선 안에 반복될 수 있어 가능한 승·하차 순번 조합을 모두 만든다.
        List<Candidate> candidates = new ArrayList<>();
        for (BusRouteMaster route : routeCandidates) {
            candidates.addAll(matchStops(route, request));
        }
        if (candidates.isEmpty()) {
            return partialRouteOnly(routeCandidates, "STOP_OR_ORDER_NOT_FOUND");
        }

        // 3단계: 중간 정류장 일치율을 우선하고, 결과 재현을 위해 ID와 순번을 후속 정렬 기준으로 둔다.
        candidates.sort(Comparator
                .comparingDouble(Candidate::score).reversed()
                .thenComparing(Comparator.comparingDouble(Candidate::passingRatio).reversed())
                .thenComparing(candidate -> candidate.route().routeId())
                .thenComparingInt(Candidate::startIndex)
                .thenComparingInt(Candidate::endIndex));

        // 4단계: 점수가 사실상 같은 서로 다른 후보가 남으면 절대 첫 번째 후보를 임의 선택하지 않는다.
        Candidate best = candidates.get(0);
        long tiedCandidates = candidates.stream()
                .filter(candidate -> Math.abs(candidate.score() - best.score()) < TIE_EPSILON)
                .map(Candidate::identity)
                .distinct()
                .count();
        if (tiedCandidates > 1) {
            return failed(BusMappingStatus.AMBIGUOUS, "MULTIPLE_TOP_CANDIDATES", candidates.size());
        }

        // 5단계: 점수, 중간 정류장 비율, 좌표 방향 중 하나라도 부족하면 공식 구간을 발급하지 않는다.
        if (best.score() < EXACT_SCORE
                || best.passingRatio() < EXACT_PASSING_RATIO
                || Boolean.FALSE.equals(best.geometryConsistent())) {
            return new BusMappingResult(
                    BusMappingStatus.PARTIAL,
                    best.route().routeId(),
                    best.route().routeName(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    round(best.score()),
                    round(best.passingRatio()),
                    candidates.size(),
                    Boolean.FALSE.equals(best.geometryConsistent())
                            ? "GEOMETRY_DIRECTION_MISMATCH"
                            : "PASSING_STOPS_NOT_CONFIRMED",
                    List.of()
            );
        }

        // 6단계: 모든 검증을 통과한 유일 후보만 실제 인접 정류장 구간으로 전개한다.
        return exact(best, candidates.size());
    }

    /** 공식 마스터를 정규화 노선명 → 노선 목록으로 인덱싱한다. */
    private Map<String, List<BusRouteMaster>> indexRoutes(Collection<BusRouteMaster> routes) {
        Map<String, List<BusRouteMaster>> index = new HashMap<>();
        for (BusRouteMaster route : routes) {
            if (route == null || route.routeId() == null || route.routeName() == null || route.stops().isEmpty()) {
                continue;
            }
            String normalizedName = normalizer.normalizeRouteName(route.routeName());
            if (!normalizedName.isEmpty()) {
                index.computeIfAbsent(normalizedName, ignored -> new ArrayList<>()).add(route);
            }
        }
        index.replaceAll((key, value) -> List.copyOf(value));
        return Map.copyOf(index);
    }

    /** 카카오가 제시한 여러 탑승 가능 노선에서 중복 route_id를 제거한 공식 후보를 찾는다. */
    private List<BusRouteMaster> findRouteCandidates(List<String> providerRouteNames) {
        Set<String> routeIds = new LinkedHashSet<>();
        List<BusRouteMaster> result = new ArrayList<>();
        for (String providerName : providerRouteNames) {
            String normalizedName = normalizer.normalizeRouteName(providerName);
            for (BusRouteMaster route : routesByNormalizedName.getOrDefault(normalizedName, List.of())) {
                if (routeIds.add(route.routeId())) {
                    result.add(route);
                }
            }
        }
        return result;
    }

    /**
     * 공식 노선 하나에서 가능한 모든 정방향 승차·하차 조합을 만든다.
     * 기본 점수 0.75는 노선명 0.25, 승차명 0.20, 하차명 0.20, 순서 0.10을 모두 통과한 값이며,
     * 중간 정류장 순차 일치율이 나머지 0.25를 결정한다. 좌표 방향이 반대면 0.10을 감점한다.
     */
    private List<Candidate> matchStops(BusRouteMaster route, BusMappingRequest request) {
        String normalizedFrom = normalizer.normalizeStopName(request.fromStopName());
        String normalizedTo = normalizer.normalizeStopName(request.toStopName());
        List<BusStopMaster> stops = route.stops();
        List<Candidate> result = new ArrayList<>();

        // endIndex를 startIndex 다음부터 탐색하는 것 자체가 승차 순번 < 하차 순번 조건을 강제한다.
        for (int startIndex = 0; startIndex < stops.size(); startIndex++) {
            if (!normalizer.normalizeStopName(stops.get(startIndex).stopName()).equals(normalizedFrom)) {
                continue;
            }
            for (int endIndex = startIndex + 1; endIndex < stops.size(); endIndex++) {
                if (!normalizer.normalizeStopName(stops.get(endIndex).stopName()).equals(normalizedTo)) {
                    continue;
                }
                double passingRatio = passingMatchRatio(
                        request.passingStopNames(), stops.subList(startIndex, endIndex + 1));
                Boolean geometryConsistent = geometryConsistent(
                        request.geometry(), stops.get(startIndex), stops.get(endIndex));
                double score = 0.75 + (0.25 * passingRatio);
                if (Boolean.FALSE.equals(geometryConsistent)) {
                    score -= 0.10;
                }
                result.add(new Candidate(
                        route, startIndex, endIndex, passingRatio, score, geometryConsistent));
            }
        }
        return result;
    }

    /**
     * 카카오 정류장 목록이 공식 구간에서 같은 순서로 등장하는 비율을 계산한다.
     * 두 포인터 부분수열 비교를 사용하므로 카카오가 일부 중간 정류장을 생략해도 순서가 맞으면 인정하지만,
     * 순서가 뒤집힌 정류장은 일치로 세지 않는다.
     */
    private double passingMatchRatio(List<String> providerNames, List<BusStopMaster> officialStops) {
        List<String> normalizedProvider = providerNames.stream()
                .map(normalizer::normalizeStopName)
                .filter(name -> !name.isEmpty())
                .toList();
        // 카카오가 중간 정류장 목록을 주지 않은 경우 이름·순번 검증만으로 판단할 수 있게 중립값을 사용한다.
        if (normalizedProvider.isEmpty()) {
            return 1.0;
        }

        List<String> normalizedOfficial = officialStops.stream()
                .map(BusStopMaster::stopName)
                .map(normalizer::normalizeStopName)
                .toList();
        int matched = 0;
        int officialIndex = 0;
        for (String providerName : normalizedProvider) {
            while (officialIndex < normalizedOfficial.size()
                    && !normalizedOfficial.get(officialIndex).equals(providerName)) {
                officialIndex++;
            }
            if (officialIndex == normalizedOfficial.size()) {
                continue;
            }
            matched++;
            officialIndex++;
        }
        return (double) matched / normalizedProvider.size();
    }

    /**
     * 좌표 증거가 있으면 path 첫 점이 승차점에, 마지막 점이 하차점에 더 가까운지 확인한다.
     * 좌표가 없으면 실패가 아니라 '검증 불가(null)'로 반환해 이름·순서 결과를 유지한다.
     */
    private Boolean geometryConsistent(
            List<Coordinate> geometry,
            BusStopMaster start,
            BusStopMaster end
    ) {
        if (geometry.size() < 2
                || start.longitude() == null || start.latitude() == null
                || end.longitude() == null || end.latitude() == null) {
            return null;
        }
        // 절대 거리가 아니라 어느 끝점에 더 가까운지만 비교하므로 제곱근 계산은 생략해도 결과가 같다.
        Coordinate first = geometry.get(0);
        Coordinate last = geometry.get(geometry.size() - 1);
        double firstToStart = squaredDistance(first, start.longitude(), start.latitude());
        double firstToEnd = squaredDistance(first, end.longitude(), end.latitude());
        double lastToStart = squaredDistance(last, start.longitude(), start.latitude());
        double lastToEnd = squaredDistance(last, end.longitude(), end.latitude());
        return firstToStart <= firstToEnd && lastToEnd <= lastToStart;
    }

    private double squaredDistance(Coordinate point, double longitude, double latitude) {
        double longitudeDifference = point.longitude() - longitude;
        double latitudeDifference = point.latitude() - latitude;
        return longitudeDifference * longitudeDifference + latitudeDifference * latitudeDifference;
    }

    /** 확정한 승차~하차 범위를 A→B, B→C 형태의 공식 인접 구간으로 전개한다. */
    private BusMappingResult exact(Candidate candidate, int candidateCount) {
        List<BusStopMaster> stops = candidate.route().stops();
        BusStopMaster start = stops.get(candidate.startIndex());
        BusStopMaster end = stops.get(candidate.endIndex());
        List<MappedBusSegment> segments = new ArrayList<>();
        // 임의의 모든 정류장 쌍을 만들지 않고, 공식 목록에서 실제로 이웃한 구간만 생성한다.
        for (int index = candidate.startIndex(); index < candidate.endIndex(); index++) {
            BusStopMaster from = stops.get(index);
            BusStopMaster to = stops.get(index + 1);
            segments.add(new MappedBusSegment(
                    segments.size() + 1,
                    candidate.route().routeId(),
                    from.stopId(),
                    to.stopId(),
                    from.sequence(),
                    to.sequence()
            ));
        }
        return new BusMappingResult(
                BusMappingStatus.EXACT,
                candidate.route().routeId(),
                candidate.route().routeName(),
                start.stopId(),
                end.stopId(),
                start.sequence(),
                end.sequence(),
                "FORWARD",
                round(candidate.score()),
                round(candidate.passingRatio()),
                candidateCount,
                "NONE",
                segments
        );
    }

    /** 노선은 찾았지만 정류장 ID와 순번을 확정하지 못한 상태를 만든다. */
    private BusMappingResult partialRouteOnly(List<BusRouteMaster> routes, String reason) {
        BusRouteMaster onlyRoute = routes.size() == 1 ? routes.get(0) : null;
        return new BusMappingResult(
                BusMappingStatus.PARTIAL,
                onlyRoute == null ? null : onlyRoute.routeId(),
                onlyRoute == null ? null : onlyRoute.routeName(),
                null, null, null, null, null,
                0.25,
                0.0,
                0,
                reason,
                List.of()
        );
    }

    /** 공식 노선 미발견 또는 다중 후보 상태처럼 선택 결과를 노출하면 안 되는 실패 결과를 만든다. */
    private BusMappingResult failed(BusMappingStatus status, String reason, int candidateCount) {
        return new BusMappingResult(
                status,
                null, null, null, null, null, null, null,
                0.0,
                0.0,
                candidateCount,
                reason,
                List.of()
        );
    }

    /** 로그·응답·회귀 테스트에서 점수 표현이 흔들리지 않도록 소수 넷째 자리로 고정한다. */
    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    /** 외부에 노출하지 않는 후보 증거. identity는 동률 후보가 실제로 다른 공식 구간인지 판별한다. */
    private record Candidate(
            BusRouteMaster route,
            int startIndex,
            int endIndex,
            double passingRatio,
            double score,
            Boolean geometryConsistent
    ) {
        private String identity() {
            BusStopMaster start = route.stops().get(startIndex);
            BusStopMaster end = route.stops().get(endIndex);
            return route.routeId() + ':' + start.stopId() + ':' + end.stopId();
        }
    }
}
