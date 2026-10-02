package com.shortlink.controller;

import com.shortlink.common.result.Result;
import com.shortlink.dto.StatsRespDTO;
import com.shortlink.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短链统计查询接口（V1.3）
 */
@Tag(name = "短链统计")
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @Operation(summary = "查询短链某日统计（date 不传则查当天）")
    @GetMapping("/{code}")
    public Result<StatsRespDTO> stats(@PathVariable String code,
                                     @RequestParam(required = false) String date) {
        return Result.success(statsService.queryStats(code, date));
    }
}
