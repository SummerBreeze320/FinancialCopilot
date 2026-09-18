package com.financial.copilot.data.rag.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.data.rag.dto.MetricRawJsonDto;
import com.financial.copilot.data.rag.dto.SectorRawJsonDto;
import com.financial.copilot.domain.shared.rag.entity.RagFundMetric;
import com.financial.copilot.domain.shared.rag.entity.RagFundSector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * <h1>基金指标与板块分类树 JSON 数据预处理器</h1>
 * <p>
 * 核心功能：
 * <ul>
 *   <li>解析 <code>metrics.json</code>（169 项指标），标准化别名、支持用途与 Embedding 拼接文本；</li>
 *   <li>解析 <code>sectors.json</code>（1,712 项板块），在内存中基于广度优先遍历 (BFS) 动态计算树层级深度 (<code>tree_level</code>) 并拼接完整祖先面包屑路径 (<code>full_path_names</code>)；</li>
 *   <li>增强向量特征文本，将多层级上下级路径注入向量表达，极大提高语义召回准确率。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricSectorDataParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析 metrics.json 输入流并转换为领域实体 {@link RagFundMetric} 列表
     *
     * @param inputStream 指标 JSON 数据流
     * @return 预处理后的指标实体列表
     * @throws IOException 数据读取或反序列化失败时抛出
     */
    public List<RagFundMetric> parseMetrics(InputStream inputStream) throws IOException {
        List<MetricRawJsonDto> rawList = objectMapper.readValue(inputStream, new TypeReference<>() {});
        List<RagFundMetric> result = new ArrayList<>(rawList.size());

        for (MetricRawJsonDto raw : rawList) {
            if (raw.getMnemonic() == null || raw.getMnemonic().isBlank()) {
                continue;
            }
            String name = (raw.getIndexName() != null && !raw.getIndexName().isBlank())
                    ? raw.getIndexName() : raw.getMnemonic();

            String embText = raw.getEmbeddingText();
            if (embText == null || embText.isBlank()) {
                embText = name + " " + raw.getMnemonic() + " " + (raw.getDescription() != null ? raw.getDescription() : "");
            }

            RagFundMetric metric = RagFundMetric.builder()
                    .mnemonic(raw.getMnemonic())
                    .indexName(name)
                    .parentName(raw.getParentName() != null ? raw.getParentName() : "通用指标")
                    .description(raw.getDescription())
                    .embeddingText(embText.trim())
                    .sourceIndicatorId(raw.getSourceIndicatorId())
                    .supportedUsage(raw.getSupportedUsage() != null ? raw.getSupportedUsage() : List.of())
                    .applicableProducts(raw.getApplicableProducts())
                    .aliases(raw.getAliases() != null ? raw.getAliases() : List.of())
                    .version(raw.getVersion() != null ? raw.getVersion() : 1)
                    .enabled(raw.getEnabled() != null ? raw.getEnabled() : true)
                    .build();

            result.add(metric);
        }

        log.info("[RAG-PARSER] 成功解析指标数量: count={}", result.size());
        return result;
    }

    /**
     * 解析 sectors.json 输入流，在内存构建拓扑树，计算 tree_level 和 full_path_names
     *
     * @param inputStream 板块 JSON 数据流
     * @return 增强层级与祖先面包屑路径后的板块实体列表
     * @throws IOException 数据读取或反序列化失败时抛出
     */
    public List<RagFundSector> parseSectors(InputStream inputStream) throws IOException {
        List<SectorRawJsonDto> rawList = objectMapper.readValue(inputStream, new TypeReference<>() {});
        Map<String, SectorRawJsonDto> nodeMap = new LinkedHashMap<>();
        Map<String, List<SectorRawJsonDto>> childrenMap = new HashMap<>();

        for (SectorRawJsonDto dto : rawList) {
            if (dto.getSourceSectorId() == null || dto.getSourceSectorId().isBlank()) {
                continue;
            }
            nodeMap.put(dto.getSourceSectorId(), dto);
            if (dto.getParentId() != null && !dto.getParentId().isBlank()) {
                childrenMap.computeIfAbsent(dto.getParentId(), k -> new ArrayList<>()).add(dto);
            }
        }

        // 识别根节点集合 (parent_id 为空或者指向不存在的节点)
        List<SectorRawJsonDto> roots = new ArrayList<>();
        for (SectorRawJsonDto dto : nodeMap.values()) {
            if (dto.getParentId() == null || dto.getParentId().isBlank() || !nodeMap.containsKey(dto.getParentId())) {
                roots.add(dto);
            }
        }

        Map<String, Integer> levelMap = new HashMap<>();
        Map<String, String> pathMap = new HashMap<>();

        // BFS 广度优先遍历计算深度与全路径
        Queue<SectorRawJsonDto> queue = new LinkedList<>(roots);
        for (SectorRawJsonDto r : roots) {
            levelMap.put(r.getSourceSectorId(), 0);
            pathMap.put(r.getSourceSectorId(), r.getName());
        }

        while (!queue.isEmpty()) {
            SectorRawJsonDto current = queue.poll();
            String currId = current.getSourceSectorId();
            int currLevel = levelMap.getOrDefault(currId, 0);
            String currPath = pathMap.getOrDefault(currId, current.getName());

            List<SectorRawJsonDto> children = childrenMap.getOrDefault(currId, Collections.emptyList());
            for (SectorRawJsonDto child : children) {
                levelMap.put(child.getSourceSectorId(), currLevel + 1);
                pathMap.put(child.getSourceSectorId(), currPath + " > " + child.getName());
                queue.offer(child);
            }
        }

        List<RagFundSector> result = new ArrayList<>(nodeMap.size());
        for (SectorRawJsonDto dto : nodeMap.values()) {
            String sid = dto.getSourceSectorId();
            int level = levelMap.getOrDefault(sid, 0);
            String fullPath = pathMap.getOrDefault(sid, dto.getName());

            // 增强 embedding_text：附带祖先路径以提升层级命中率
            String embText = dto.getEmbeddingText();
            if (embText == null || embText.isBlank()) {
                embText = dto.getName() + " " + (dto.getDescription() != null ? dto.getDescription() : "");
            }
            if (!embText.contains(fullPath)) {
                embText = embText + " " + fullPath;
            }

            RagFundSector sector = RagFundSector.builder()
                    .sectorId(sid)
                    .parentId(dto.getParentId())
                    .name(dto.getName())
                    .nameEn(dto.getNameEn())
                    .aliases(dto.getAliases() != null ? dto.getAliases() : List.of())
                    .description(dto.getDescription())
                    .embeddingText(embText.trim())
                    .isLeaf(dto.getIsLeaf() != null ? dto.getIsLeaf() : true)
                    .elementType(dto.getElementType() != null ? dto.getElementType() : 6)
                    .treeLevel(level)
                    .fullPathNames(fullPath)
                    .enabled(dto.getEnabled() != null ? dto.getEnabled() : true)
                    .build();

            result.add(sector);
        }

        log.info("[RAG-PARSER] 成功解析板块分类树: totalCount={}, rootsCount={}", result.size(), roots.size());
        return result;
    }
}
