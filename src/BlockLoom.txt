package com.CatFish.loommod.blocks.loom;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import com.CatFish.loommod.LoomMod;
import com.CatFish.loommod.tileentity.TileEntityLoom;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import java.util.ArrayList;

public class BlockLoom extends BlockContainer {

    // 纹理数组 [进度][面]（面索引：0=底,1=顶,2=北,3=南,4=西,5=东）
    private IIcon[][] icons = new IIcon[8][6];
    private String[] progressTextures = { "loomH", "loomG", "loomF", "loomE", "loomD", "loomC", "loomB", "loomA" };

    public BlockLoom(Material material) {
        super(material);
        setHardness(2.0f);
        setStepSound(soundTypeStone);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityLoom();
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
                                    float hitY, float hitZ) {
        if (world.isRemote) return true;
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileEntityLoom) {
            ((TileEntityLoom) te).onActivated(player);
        }
        return true;
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        int facing = MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        int[] mapping = { 0,1,2,3 }; // 原 facing: 0南->2,1西->3,2北->0,3东->1
        world.setBlockMetadataWithNotify(x, y, z, mapping[facing], 2);
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        super.breakBlock(world, x, y, z, block, meta);
    }

    @Override
    public int damageDropped(int meta) {
        return 0; // 掉落时忽略方向
    }

    // ========== 渲染配置 ==========
    @Override
    public boolean renderAsNormalBlock() {
        return false; // 使用自定义渲染
    }

    @Override
    public int getRenderType() {
        return -1; // 使用 TileEntitySpecialRenderer
    }

    @Override
    public boolean isOpaqueCube() {
        return false; // 非不透明立方体，允许透明纹理
    }

    @Override
    public ArrayList<ItemStack> getDrops(World world, int x, int y, int z, int metadata, int fortune) {
        ArrayList<ItemStack> drops = new ArrayList<>();
        drops.add(new ItemStack(LoomMod.loomGenerator));
        return drops;
    }

    // ========== 纹理注册 ==========
    @SideOnly(Side.CLIENT)
    @Override
    public void registerBlockIcons(IIconRegister reg) {
        String modId = LoomMod.MODID;
        for (int p = 0; p < 8; p++) {
            // 底面/侧面复用
            icons[p][0] = reg.registerIcon(modId + ":loom_bottom");
            icons[p][2] = reg.registerIcon(modId + ":loom_side");
            icons[p][4] = reg.registerIcon(modId + ":loom_side");
            icons[p][5] = reg.registerIcon(modId + ":loom_side");

            // 顶面/正面随进度变化
            String progressTex = progressTextures[p];
            icons[p][1] = reg.registerIcon(modId + ":loom_top_" + progressTex);
            icons[p][3] = reg.registerIcon(modId + ":loom_front_" + progressTex);
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public IIcon getIcon(IBlockAccess world, int x, int y, int z, int side) {
        TileEntity te = world.getTileEntity(x, y, z);
        int progress = 0;
        if (te instanceof TileEntityLoom) {
            progress = ((TileEntityLoom) te).getProgress();
        }
        int meta = world.getBlockMetadata(x, y, z);
        return getRotatedIcon(progress, side, meta);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public IIcon getIcon(int side, int meta) {
        return getRotatedIcon(0, side, meta);
    }

    /**
     * 根据进度、面和方向返回正确的图标
     */
    private IIcon getRotatedIcon(int progress, int side, int meta) {
        // 钳位进度（防止数组越界，只有 0~7 阶段纹理）
        progress = Math.min(progress, 7);

        // 确定正面方向：meta 0=北,1=东,2=南,3=西
        int frontSide;
        switch (meta) {
            case 0: frontSide = 2; break; // 北
            case 1: frontSide = 5; break; // 东
            case 2: frontSide = 3; break; // 南
            case 3: frontSide = 4; break; // 西
            default: frontSide = 3;
        }

        if (side == 0) return icons[progress][0]; // 底面
        if (side == 1) return icons[progress][1]; // 顶面
        if (side == frontSide) return icons[progress][3]; // 正面
        return icons[progress][2]; // 其他侧面
    }
}
