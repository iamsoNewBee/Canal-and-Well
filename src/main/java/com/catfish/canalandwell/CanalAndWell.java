package com.catfish.canalandwell;

import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.catfish.canalandwell.block.BlockCanal;
import com.catfish.canalandwell.tileentity.TileEntityCanal;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.registry.GameRegistry;

@Mod(modid = CanalAndWell.MODID, version = Tags.VERSION, name = "CanalAndWell", acceptedMinecraftVersions = "[1.7.10]")
public class CanalAndWell {

    public static final String MODID = "canalandwell";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.catfish.canalandwell.ClientProxy", serverSide = "com.catfish.canalandwell.CommonProxy")
    public static CommonProxy proxy;

    // 方块实例 —— 三种材质变体
    public static BlockCanal blockCanal;      // 石质
    public static BlockCanal blockCanalDirt;  // 土质
    public static BlockCanal blockCanalSand;  // 沙质

    // 创造标签页
    public static CreativeTabs canalTab = new CreativeTabs("canal") {
        @Override
        public Item getTabIconItem() {
            return Item.getItemFromBlock(blockCanal);
        }
    };

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);

        // 注册水渠方块 —— 三种材质变体
        blockCanal = new BlockCanal(BlockCanal.Variant.STONE);
        blockCanalDirt = new BlockCanal(BlockCanal.Variant.DIRT);
        blockCanalSand = new BlockCanal(BlockCanal.Variant.SAND);

        blockCanal.setCreativeTab(canalTab);
        blockCanalDirt.setCreativeTab(canalTab);
        blockCanalSand.setCreativeTab(canalTab);

        GameRegistry.registerBlock(blockCanal, "canal");
        GameRegistry.registerBlock(blockCanalDirt, "canal_dirt");
        GameRegistry.registerBlock(blockCanalSand, "canal_sand");

        // 注册 TileEntity（三种变体共用同一个 TE 类）
        GameRegistry.registerTileEntity(TileEntityCanal.class, MODID + ":canal_te");

        LOG.info("CanalAndWell blocks registered (stone, dirt, sand).");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }
}
