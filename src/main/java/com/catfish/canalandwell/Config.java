package com.catfish.canalandwell;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * CanalAndWell 配置文件
 * 管理水渠的水流更新频率、传播范围、封闭状态等参数。
 */
public class Config {

    // ───────────────── 水流设置 ─────────────────
    /** 水流传播检查间隔 (tick)，默认每 10 tick (0.5秒) */
    public static int tickRate = 10;
    /** 水流最大传播距离 (方块数)，默认 64 */
    public static int waterFlowRange = 64;
    /** 水渠破坏时是否清理相邻流水，默认 true */
    public static boolean cleanupFlowingWater = true;
    /** 是否允许玻璃瓶取水，默认 true */
    public static boolean enableBottleFill = true;
    /** 连锁干燥每跳延迟 (tick)，默认 2（每跳 0.1 秒） */
    public static int chainDryDelay = 2;
    /** 湿润传播每跳延迟 (tick)，默认 5（每跳 0.25 秒） */
    public static int waterSpreadDelay = 5;

    // ───────────────── 流水方块设置 ─────────────────
    /** 水渠湿润时产出的流体方块名称，默认 minecraft:water */
    public static String flowingFluidBlockName = "minecraft:water";
    /** 产出的流体方块 metadata，默认 1（流动水） */
    public static int flowingFluidMeta = 1;

    // ───────────────── 通用设置 ─────────────────
    /** 是否在日志中输出水渠状态更新信息 */
    public static boolean debugLogging = false;

    /**
     * 从配置文件同步所有设置。
     * 在 preInit 阶段由 CommonProxy 调用。
     */
    public static void synchronizeConfiguration(File configFile) {
        Configuration cfg = new Configuration(configFile);

        try {
            cfg.load();

            // ── 水流类别 ──
            String catWater = "water flow";
            tickRate = cfg.getInt("tickRate", catWater, tickRate, 1, 200,
                "Water propagation check interval in ticks. Lower = faster flow. (default: 10, ~0.5s)");
            waterFlowRange = cfg.getInt("waterFlowRange", catWater, waterFlowRange, 4, 512,
                "Maximum distance (in blocks) water can spread from source through canals. (default: 64)");
            cleanupFlowingWater = cfg.getBoolean("cleanupFlowingWater", catWater, cleanupFlowingWater,
                "If true, breaking a wet canal removes adjacent flowing water blocks. (default: true)");

            // ── 流水产出设置 ──
            flowingFluidBlockName = cfg.getString("flowingFluidBlockName", catWater, flowingFluidBlockName,
                "Block ID (e.g. 'minecraft:water') spawned when a wet canal outputs fluid to adjacent spaces. (default: minecraft:water)");
            flowingFluidMeta = cfg.getInt("flowingFluidMeta", catWater, flowingFluidMeta, 0, 15,
                "Metadata for the spawned fluid block. 0 = source, 1 = flowing. (default: 1)");

            // ── 延迟设置 ──
            chainDryDelay = cfg.getInt("chainDryDelay", catWater, chainDryDelay, 0, 100,
                "Delay in ticks between each hop of chain drying when water source is lost. 0 = instant. (default: 2)");
            waterSpreadDelay = cfg.getInt("waterSpreadDelay", catWater, waterSpreadDelay, 1, 100,
                "Delay in ticks between each hop of water spread through dry canals. (default: 5)");

            // ── 交互类别 ──
            String catInteract = "interaction";
            enableBottleFill = cfg.getBoolean("enableBottleFill", catInteract, enableBottleFill,
                "If true, right-clicking a wet canal with a glass bottle fills it into a water bottle. (default: true)");

            // ── 调试类别 ──
            String catDebug = "debug";
            debugLogging = cfg.getBoolean("debugLogging", catDebug, debugLogging,
                "Enable detailed console logging for water propagation and connection updates. (default: false)");

        } catch (Exception e) {
            CanalAndWell.LOG.error("Failed to load CanalAndWell config: " + e.getMessage());
        } finally {
            if (cfg.hasChanged()) {
                cfg.save();
            }
        }
    }
}
