
package com.ccb.jx.seq.controller;

import com.ccb.jx.seq.buffer.DoubleBuffer;
import com.ccb.jx.seq.buffer.Segment;
import com.ccb.jx.seq.core.SequenceService;
import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;
import com.ccb.jx.seq.model.Mode;
import com.ccb.jx.seq.model.SequenceConfig;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.List;

/**
 * 序列监控管理接口。
 * <p>
 * 提供只读的 REST 接口，用于查询序列的当前状态和配置信息。
 * 所有接口均为 GET 请求，不对外暴露任何修改操作。
 * </p>
 *
 * <h3>接口列表</h3>
 * <ul>
 *   <li>{@code GET /sequence/{seqName}} — 查询指定序列的完整状态</li>
 *   <li>{@code GET /sequence/list} — 查询所有序列的概要列表</li>
 * </ul>
 *
 * @author XZJ
 */
@ResponseBody
@RequestMapping("/sequence")
public class SequenceController {

    /** 序列名称最大长度限制 */
    private static final int SEQ_NAME_MAX_LENGTH = 64;

    private final SequenceService sequenceService;

    /**
     * 构造序列监控控制器。
     *
     * @param sequenceService 序列核心服务，不能为 {@code null}
     */
    public SequenceController(SequenceService sequenceService) {
        this.sequenceService = sequenceService;
    }

    /**
     * 查询指定序列的完整状态信息。
     * <p>
     * 返回序列的关键状态信息：名称、模式、当前值、剩余数量和总容量。
     * 序列名称长度限制为 {@value #SEQ_NAME_MAX_LENGTH} 个字符。
     * </p>
     *
     * @param seqName 序列名称，最大长度 {@value #SEQ_NAME_MAX_LENGTH}
     * @return 序列状态信息视图对象
     * @throws SequenceException 如果序列名称超长或序列不存在
     */
    @GetMapping("/{seqName}")
    public SequenceInfoVO getSequenceInfo(@PathVariable String seqName) {
        validateSeqName(seqName);

        SequenceService.SequenceInfo info = sequenceService.getSequenceInfo(seqName);
        SequenceConfig config = info.getConfig();

        long currentValue = config.getMaxId();
        long remaining = info.getRemaining();
        long totalCapacity = 0;

        if (config.getMode() == Mode.CACHED) {
            DoubleBuffer buffer = info.getBuffer();
            if (buffer != null) {
                Segment current = buffer.getCurrent();
                if (current != null) {
                    totalCapacity = current.getEnd() - current.getStart() + 1;
                    // currentValue = current.getCurrent() - 1，但需排除号段未使用时返回 start-1 的情况
                    long rawCurrent = current.getCurrent();
                    currentValue = rawCurrent > current.getStart() ? rawCurrent - 1 : rawCurrent;
                }
            }
        }

        return new SequenceInfoVO(
                config.getSeqName(),
                config.getMode().name(),
                currentValue,
                remaining,
                totalCapacity
        );
    }

    /**
     * 查询所有序列的概要列表。
     * <p>
     * 返回系统内所有已注册序列的基本配置信息，包括：
     * 序列名称、模式、当前最大值、步长和描述。
     * </p>
     *
     * @return 序列配置概要列表
     */
    @GetMapping("/list")
    public List<SequenceSummaryVO> listAll() {
        List<SequenceConfig> configs = sequenceService.getAllConfigs();
        List<SequenceSummaryVO> result = new ArrayList<>(configs.size());

        for (SequenceConfig config : configs) {
            result.add(new SequenceSummaryVO(
                    config.getSeqName(),
                    config.getMode().name(),
                    config.getMaxId(),
                    config.getStep(),
                    config.getDescription()
            ));
        }

        return result;
    }

    /**
     * 校验序列名称长度。
     *
     * @param seqName 序列名称
     * @throws SequenceException 如果序列名称超过 {@value #SEQ_NAME_MAX_LENGTH} 个字符
     */
    private void validateSeqName(String seqName) {
        if (seqName != null && seqName.length() > SEQ_NAME_MAX_LENGTH) {
            throw new SequenceException(SequenceErrorCode.INVALID_CONFIG,
                    "seqName exceeds maximum length of " + SEQ_NAME_MAX_LENGTH
                            + ": got " + seqName.length() + " characters");
        }
    }
}
