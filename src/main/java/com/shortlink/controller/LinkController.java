package com.shortlink.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.shortlink.common.result.Result;
import com.shortlink.dto.LinkCreateReqDTO;
import com.shortlink.dto.LinkPageReqDTO;
import com.shortlink.dto.LinkRespDTO;
import com.shortlink.dto.LinkUpdateReqDTO;
import com.shortlink.service.LinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短链管理接口
 */
@Tag(name = "短链管理")
@RestController
@RequestMapping("/api/v1/link")
@RequiredArgsConstructor
public class LinkController {

    private final LinkService linkService;

    @Operation(summary = "创建短链")
    @PostMapping("/create")
    public Result<LinkRespDTO> create(@RequestBody @Valid LinkCreateReqDTO reqDTO) {
        return Result.success(linkService.createLink(reqDTO));
    }

    @Operation(summary = "修改短链")
    @PutMapping("/update")
    public Result<Void> update(@RequestBody @Valid LinkUpdateReqDTO reqDTO) {
        linkService.updateLink(reqDTO);
        return Result.success();
    }

    @Operation(summary = "删除短链（逻辑删除）")
    @DeleteMapping("/del")
    public Result<Void> delete(@RequestParam Long id) {
        linkService.deleteLink(id);
        return Result.success();
    }

    @Operation(summary = "分页查询短链")
    @PostMapping("/page")
    public Result<IPage<LinkRespDTO>> page(@RequestBody @Valid LinkPageReqDTO reqDTO) {
        return Result.success(linkService.pageLink(reqDTO));
    }
}
