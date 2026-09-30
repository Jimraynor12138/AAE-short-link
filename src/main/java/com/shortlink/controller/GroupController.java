package com.shortlink.controller;

import com.shortlink.common.result.Result;
import com.shortlink.dto.GroupCreateReqDTO;
import com.shortlink.dto.GroupRespDTO;
import com.shortlink.service.GroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 短链分组接口
 */
@Tag(name = "短链分组")
@RestController
@RequestMapping("/api/v1/group")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @Operation(summary = "创建分组")
    @PostMapping("/create")
    public Result<String> create(@RequestBody @Valid GroupCreateReqDTO reqDTO) {
        return Result.success(groupService.createGroup(reqDTO));
    }

    @Operation(summary = "查询全部分组")
    @GetMapping("/list")
    public Result<List<GroupRespDTO>> list() {
        return Result.success(groupService.listGroup());
    }

    @Operation(summary = "删除分组")
    @DeleteMapping("/del")
    public Result<Void> delete(@RequestParam String gid) {
        groupService.deleteGroup(gid);
        return Result.success();
    }
}
