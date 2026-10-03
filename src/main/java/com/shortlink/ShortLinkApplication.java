package com.shortlink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 短链接系统启动类
 *
 * EnableScheduling：V2 起统计快照按固定间隔批量落库（见 StatsSnapshotServiceImpl）
 */
@EnableScheduling
@SpringBootApplication
public class ShortLinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShortLinkApplication.class, args);
    }
}
