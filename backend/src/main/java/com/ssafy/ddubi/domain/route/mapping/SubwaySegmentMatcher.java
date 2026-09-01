package com.ssafy.ddubi.domain.route.mapping;

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
 * 카카오의 이름 기반 SUBWAY step을 공식 호선과 인접 역 구간으로 변환한다.
 *
 * <ol>
 *     <li>카카오 vehicles.name을 정규화해 공식 호선·방향 후보를 찾는다.</li>
 *     <li>각 방향에서 승차역보다 뒤에 있는 하차역 조합만 만든다.</li>
 *     <li>카카오 중간역이 공식 목록에서 같은 순서로 등장하는지 검증한다.</li>
 *     <li>최상위 공식 후보가 하나일 때만 방향과 역 ID를 EXACT로 확정한다.</li>
 *     <li>승차~하차 범위를 실제 인접 역 구간 목록으로 전개한다.</li>
 * </ol>
 *
 * <p>DB, Spring, 카카오 DTO와 공용 segment_id 형식에는 의존하지 않는다. 공식 마스터를
 * 파일이나 DB에서 읽어 주입할 수 있으며, 동일 입력은 항상 동일 결과를 만든다.</p>
 */
public class SubwaySegmentMatcher {

    private static final double EXACT_SCORE = 0.90;
    private static final double EXACT_PASSING_RATIO = 0.80;
    private static final double TIE_EPSILON = 0.01;

    private final TransitNameNormalizer normalizer;
    private final Map<String, List<SubwayLineMaster>> linesByNormalizedName;

    public SubwaySegmentMatcher(Collection<SubwayLineMaster> lines) {
        this(lines, new TransitNameNormalizer());
    }

    public SubwaySegmentMatcher(
            Collection<SubwayLineMaster> lines,
            TransitNameNormalizer normalizer
    ) {
        this.normalizer = Objects.requireNonNull(normalizer);
        // 요청마다 전체 지하철 마스터를 순회하지 않도록 생성 시 호선명 인덱스를 만든다.
        this.linesByNormalizedName = indexLines(lines == null ? List.of() : lines);
    }

    /**
     * 카카오 SUBWAY step 하나를 공식 마스터에 매핑한다.
     * 잘못된 실시간 구간 조회를 막기 위해 EXACT 외 결과에는 인접 역 구간을 넣지 않는다.
     */
    public SubwayMappingResult match(SubwayMappingRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        List<SubwayLineMaster> lineCandidates = findLineCandidates(request.providerLineNames());
        if (lineCandidates.isEmpty()) {
            return failed(SubwayMappingStatus.UNMAPPED, "LINE_NOT_FOUND", 0);
        }

        // 동명이역과 순환선을 고려해 가능한 모든 정방향 승·하차 조합을 보존한다.
        List<Candidate> candidates = new ArrayList<>();
        for (SubwayLineMaster line : lineCandidates) {
            candidates.addAll(matchStations(line, request));
        }
        if (candidates.isEmpty()) {
            return partialLineOnly(lineCandidates, "STATION_OR_ORDER_NOT_FOUND");
        }

        candidates.sort(Comparator
                .comparingDouble(Candidate::score).reversed()
                .thenComparing(Comparator.comparingDouble(Candidate::passingRatio).reversed())
                .thenComparing(candidate -> candidate.line().subwayId())
                .thenComparing(candidate -> candidate.line().direction())
                .thenComparingInt(Candidate::startIndex)
                .thenComparingInt(Candidate::endIndex));

        Candidate best = candidates.get(0);
        long tiedCandidates = candidates.stream()
                .filter(candidate -> Math.abs(candidate.score() - best.score()) < TIE_EPSILON)
                .map(Candidate::identity)
                .distinct()
                .count();
        // 같은 점수의 노선·방향·역 조합이 둘 이상이면 배열의 첫 항목을 선택하지 않는다.
        if (tiedCandidates > 1) {
            return failed(SubwayMappingStatus.AMBIGUOUS, "MULTIPLE_TOP_CANDIDATES", candidates.size());
        }

        if (best.score() < EXACT_SCORE || best.passingRatio() < EXACT_PASSING_RATIO) {
            return new SubwayMappingResult(
                    SubwayMappingStatus.PARTIAL,
                    best.line().subwayId(),
                    best.line().lineName(),
                    null, null, null, null, null,
                    round(best.score()),
                    round(best.passingRatio()),
                    candidates.size(),
                    "PASSING_STATIONS_NOT_CONFIRMED",
                    List.of()
            );
        }

        return exact(best, candidates.size());
    }

    /** 유효한 마스터만 정규화 호선명 → 방향별 노선 목록으로 인덱싱한다. */
    private Map<String, List<SubwayLineMaster>> indexLines(Collection<SubwayLineMaster> lines) {
        Map<String, List<SubwayLineMaster>> index = new HashMap<>();
        for (SubwayLineMaster line : lines) {
            if (line == null
                    || line.subwayId() == null
                    || line.lineName() == null
                    || line.direction() == null
                    || line.stations().isEmpty()) {
                continue;
            }
            String normalizedName = normalizer.normalizeSubwayLineName(line.lineName());
            if (!normalizedName.isEmpty()) {
                index.computeIfAbsent(normalizedName, ignored -> new ArrayList<>()).add(line);
            }
        }
        index.replaceAll((key, value) -> List.copyOf(value));
        return Map.copyOf(index);
    }

    /** 카카오가 제시한 호선명에서 중복된 공식 subwayId·방향 후보를 제거한다. */
    private List<SubwayLineMaster> findLineCandidates(List<String> providerLineNames) {
        Set<String> lineKeys = new LinkedHashSet<>();
        List<SubwayLineMaster> result = new ArrayList<>();
        for (String providerName : providerLineNames) {
            String normalizedName = normalizer.normalizeSubwayLineName(providerName);
            for (SubwayLineMaster line : linesByNormalizedName.getOrDefault(normalizedName, List.of())) {
                String key = line.subwayId() + ':' + line.direction();
                if (lineKeys.add(key)) {
                    result.add(line);
                }
            }
        }
        return result;
    }

    /**
     * 한 운행 방향 안에서 승차역 순번이 하차역 순번보다 앞선 조합만 점수화한다.
     * 호선·두 역·순서가 일치한 기본 점수 0.75에 중간역 순차 일치율 0.25를 더한다.
     */
    private List<Candidate> matchStations(SubwayLineMaster line, SubwayMappingRequest request) {
        String normalizedFrom = normalizer.normalizeSubwayStationName(request.fromStationName());
        String normalizedTo = normalizer.normalizeSubwayStationName(request.toStationName());
        List<SubwayStationMaster> stations = line.stations();
        List<Candidate> result = new ArrayList<>();

        for (int startIndex = 0; startIndex < stations.size(); startIndex++) {
            if (!normalizer.normalizeSubwayStationName(stations.get(startIndex).stationName())
                    .equals(normalizedFrom)) {
                continue;
            }
            // 하차역을 다음 순번부터 찾는 조건으로 해당 방향의 실제 진행 순서를 강제한다.
            for (int endIndex = startIndex + 1; endIndex < stations.size(); endIndex++) {
                if (!normalizer.normalizeSubwayStationName(stations.get(endIndex).stationName())
                        .equals(normalizedTo)) {
                    continue;
                }
                double passingRatio = passingMatchRatio(
                        request.passingStationNames(), stations.subList(startIndex, endIndex + 1));
                result.add(new Candidate(
                        line, startIndex, endIndex, passingRatio, 0.75 + (0.25 * passingRatio)));
            }
        }
        return result;
    }

    /**
     * 카카오 역 목록을 공식 구간의 부분수열로 비교한다. 일부 중간역 생략은 허용하지만
     * 역 순서가 뒤집힌 경우는 일치로 세지 않아 잘못된 방향 확정을 막는다.
     */
    private double passingMatchRatio(
            List<String> providerNames,
            List<SubwayStationMaster> officialStations
    ) {
        List<String> normalizedProvider = providerNames.stream()
                .map(normalizer::normalizeSubwayStationName)
                .filter(name -> !name.isEmpty())
                .toList();
        if (normalizedProvider.isEmpty()) {
            return 1.0;
        }

        List<String> normalizedOfficial = officialStations.stream()
                .map(SubwayStationMaster::stationName)
                .map(normalizer::normalizeSubwayStationName)
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

    /** 확정한 승차~하차 범위를 실제 이웃한 역 쌍으로 전개한다. */
    private SubwayMappingResult exact(Candidate candidate, int candidateCount) {
        SubwayLineMaster line = candidate.line();
        List<SubwayStationMaster> stations = line.stations();
        SubwayStationMaster start = stations.get(candidate.startIndex());
        SubwayStationMaster end = stations.get(candidate.endIndex());
        List<MappedSubwaySegment> segments = new ArrayList<>();
        for (int index = candidate.startIndex(); index < candidate.endIndex(); index++) {
            SubwayStationMaster from = stations.get(index);
            SubwayStationMaster to = stations.get(index + 1);
            segments.add(new MappedSubwaySegment(
                    segments.size() + 1,
                    line.subwayId(),
                    line.lineName(),
                    line.direction(),
                    from.stationId(),
                    to.stationId(),
                    from.sequence(),
                    to.sequence()
            ));
        }
        return new SubwayMappingResult(
                SubwayMappingStatus.EXACT,
                line.subwayId(),
                line.lineName(),
                start.stationId(),
                end.stationId(),
                start.sequence(),
                end.sequence(),
                line.direction(),
                round(candidate.score()),
                round(candidate.passingRatio()),
                candidateCount,
                "NONE",
                segments
        );
    }

    /** 호선은 찾았지만 역 또는 방향을 확정하지 못한 PARTIAL 결과를 만든다. */
    private SubwayMappingResult partialLineOnly(List<SubwayLineMaster> lines, String reason) {
        SubwayLineMaster representative = lines.get(0);
        boolean sameLine = lines.stream().allMatch(line ->
                line.subwayId().equals(representative.subwayId())
                        && line.lineName().equals(representative.lineName()));
        return new SubwayMappingResult(
                SubwayMappingStatus.PARTIAL,
                sameLine ? representative.subwayId() : null,
                sameLine ? representative.lineName() : null,
                null, null, null, null, null,
                0.25,
                0.0,
                0,
                reason,
                List.of()
        );
    }

    /** 호선 미발견이나 다중 최상위 후보처럼 공식 선택을 노출하면 안 되는 결과를 만든다. */
    private SubwayMappingResult failed(SubwayMappingStatus status, String reason, int candidateCount) {
        return new SubwayMappingResult(
                status,
                null, null, null, null, null, null, null,
                0.0,
                0.0,
                candidateCount,
                reason,
                List.of()
        );
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    /** 동률 판정 identity에는 방향을 포함해 같은 역 쌍의 상·하행도 서로 다른 후보로 취급한다. */
    private record Candidate(
            SubwayLineMaster line,
            int startIndex,
            int endIndex,
            double passingRatio,
            double score
    ) {
        private String identity() {
            SubwayStationMaster start = line.stations().get(startIndex);
            SubwayStationMaster end = line.stations().get(endIndex);
            return line.subwayId() + ':' + line.direction() + ':'
                    + start.stationId() + ':' + end.stationId();
        }
    }
}
