package com.catfish.canalandwell;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        // 加载配置文件
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());

        CanalAndWell.LOG.info("CanalAndWell v" + Tags.VERSION + " loaded.");
        CanalAndWell.LOG.info("  tickRate: " + Config.tickRate + " ticks");
        CanalAndWell.LOG.info("  waterFlowRange: " + Config.waterFlowRange + " blocks");
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {}
}
