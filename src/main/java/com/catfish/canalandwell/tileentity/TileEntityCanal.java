package com.catfish.canalandwell.tileentity;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

import com.catfish.canalandwell.CanalAndWell;
import com.catfish.canalandwell.Config;
import com.catfish.canalandwell.block.BlockCanal;

/**
 * 水渠 TileEntity —— 仅存储湿水状态，处理水传播。
 *
 * 连接与形态由 BlockCanal 在渲染时动态计算（参考红石线），
 * TE 不参与连接逻辑，避免状态漂移。
 *
 * 注意：1.7.10 中 TileEntity.canUpdate() 默认 false，
 * 必须重写为 true 才能使 updateEntity() 被调用。
 *
 * ============ 客户端同步 ============
 * isWet 状态通过 S35PacketUpdateTileEntity 同步到客户端。
 * setWet(true/false) 时立即发送 packet 到所有追踪此位置的玩家，
 * 确保水渠贴图实时切换。
 */
public class TileEntityCanal extends TileEntity {

    private boolean isWet;
    private int tickCounter;

    // ── 关键：让 TE 每 tick 运行 ──
    @Override
    public boolean canUpdate() {
        return true;
    }

    public boolean isWet() { return isWet; }

    /**
     * 设置湿润状态并同步到所有客户端。
     * 服务端调用时立即发送 TE 描述包，客户端收到后更新本地 isWet 并触发重绘。
     */
    public void setWet(boolean wet) {
        if (this.isWet != wet) {
            this.isWet = wet;
            markDirty();
            if (worldObj != null) {
                // 标记方块需要重绘（更新方块 ID + meta）
                worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
                // 同步 TE 数据到所有客户端
                if (!worldObj.isRemote) {
                    sendTileEntityUpdate();
                }
            }
        }
    }

    /**
     * 向所有追踪此位置的玩家发送 TE 描述包。
     */
    private void sendTileEntityUpdate() {
        if (!(worldObj instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) worldObj;
        S35PacketUpdateTileEntity packet = new S35PacketUpdateTileEntity(
                xCoord, yCoord, zCoord, 0, getUpdateTag());
        double range = 64 * 64;
        for (EntityPlayerMP player : (List<EntityPlayerMP>) ws.playerEntities) {
            if (player.getDistanceSq(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5) <= range) {
                player.playerNetServerHandler.sendPacket(packet);
            }
        }
    }

    /** 供 sendTileEntityUpdate 使用的 NBT（仅包含需同步的字段） */
    private NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("isWet", isWet);
        return tag;
    }

    // ══════════════════════════════════════════════════════
    //  客户端同步 —— Packet 处理
    // ══════════════════════════════════════════════════════

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeToNBT(tag);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        readFromNBT(pkt.func_148857_g());
        // 触发客户端重绘
        if (worldObj != null) {
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    // ══════════════════════════════════════════════════════
    //  每 tick 水源检测 + 传播
    // ══════════════════════════════════════════════════════

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) return;

        if (++tickCounter < Config.tickRate) return;
        tickCounter = 0;

        Block block = worldObj.getBlock(xCoord, yCoord, zCoord);
        if (!(block instanceof BlockCanal)) return;

        // ── 1. 检测相邻水源 → 变湿 ──
        if (!isWet && detectWaterSource()) {
            setWet(true);
            if (Config.debugLogging) {
                CanalAndWell.LOG.info("[Canal] Wet at {}, {}, {}", xCoord, yCoord, zCoord);
            }
        }

        // ── 2. 湿润时传播到相邻干燥水渠 + 生成流水 ──
        if (isWet) {
            propagateWetness();
            spawnFlowingWater();
        }
    }

    // ══════════════════════════════════════════════════════
    //  水源检测 —— 检查四个水平方向 + 可配置流体
    // ══════════════════════════════════════════════════════

    /**
     * 检测相邻是否有水源或已湿润水渠。
     * 检测目标：
     *   1. 可配置流体方块 (meta=0, 水源)
     *   2. 原版水源方块 (minecraft:water meta=0)，作为兜底
     *   3. 已湿润的相邻水渠
     */
    public boolean detectWaterSource() {
        Block configuredFluid = getFluidBlock();

        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            if (dir == ForgeDirection.UP || dir == ForgeDirection.DOWN) continue;

            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;

            Block neighbor = worldObj.getBlock(nx, ny, nz);
            int neighborMeta = worldObj.getBlockMetadata(nx, ny, nz);

            // 可配置流体方块的水源 (meta=0)
            if (neighbor == configuredFluid && neighborMeta == 0) {
                return true;
            }

            // 原版水源方块 (始终作为兜底检测)
            if (configuredFluid != Blocks.water && neighbor == Blocks.water && neighborMeta == 0) {
                return true;
            }

            // 已湿润的相邻水渠
            if (neighbor instanceof BlockCanal) {
                TileEntity te = worldObj.getTileEntity(nx, ny, nz);
                if (te instanceof TileEntityCanal && ((TileEntityCanal) te).isWet()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════
    //  传播
    // ══════════════════════════════════════════════════════

    /**
     * 将湿润状态传播到相邻干燥水渠。
     * 被 propagateImmediate() 和 updateEntity() 共用。
     * 传播不跨变体 —— 石质/土质/沙质水渠均可互相传播。
     */
    private void propagateWetness() {
        for (ForgeDirection dir : new ForgeDirection[] {
                ForgeDirection.NORTH, ForgeDirection.SOUTH,
                ForgeDirection.WEST,  ForgeDirection.EAST}) {

            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;

            Block neighbor = worldObj.getBlock(nx, ny, nz);
            if (!(neighbor instanceof BlockCanal)) continue;

            TileEntity te = worldObj.getTileEntity(nx, ny, nz);
            if (!(te instanceof TileEntityCanal)) continue;

            TileEntityCanal ct = (TileEntityCanal) te;
            if (!ct.isWet()) {
                ct.setWet(true);
            }
        }
    }

    /**
     * 实时传播 —— 检测到水源时立即向相邻水渠扩散湿润状态，
     * 避免等待下一次 updateTick。用于处理"对已有水流的水渠系统
     * 实时增添方块"的场景。
     *
     * 传播范围限制为单跳 (1-hop)，后续传播由各 TileEntity 的
     * updateEntity() 逐 tick 接力完成。
     */
    public void propagateImmediate() {
        if (!isWet) return;
        propagateWetness();
        spawnFlowingWater();
    }

    /**
     * 在湿润水渠相邻的空气方块中生成流水。
     * 使用可配置的流体方块与 metadata。
     *
     * 生成条件：
     *   1. 水平相邻方块为空气
     *   2. 该空气方块下方为水渠或流体
     */
    private void spawnFlowingWater() {
        Block fluidBlock = getFluidBlock();
        int fluidMeta = Config.flowingFluidMeta;

        for (ForgeDirection dir : new ForgeDirection[] {
                ForgeDirection.NORTH, ForgeDirection.SOUTH,
                ForgeDirection.WEST,  ForgeDirection.EAST}) {

            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;

            if (!worldObj.isAirBlock(nx, ny, nz)) continue;

            Block below = worldObj.getBlock(nx, ny - 1, nz);
            if (below instanceof BlockCanal || below == fluidBlock || below == Blocks.water) {
                worldObj.setBlock(nx, ny, nz, fluidBlock, fluidMeta, 3);
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  流体方块解析
    // ══════════════════════════════════════════════════════

    /**
     * 解析 Config.flowingFluidBlockName 为 Block 实例。
     * 若配置的名称无效或方块不存在，回退到原版水方块。
     */
    private static Block getFluidBlock() {
        Block configured = Block.getBlockFromName(Config.flowingFluidBlockName);
        if (configured != null) {
            return configured;
        }
        CanalAndWell.LOG.warn("[Canal] Unknown fluid block '{}', falling back to minecraft:water",
                Config.flowingFluidBlockName);
        return Blocks.water;
    }

    // ══════════════════════════════════════════════════════
    //  NBT
    // ══════════════════════════════════════════════════════

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        isWet = nbt.getBoolean("isWet");
        tickCounter = nbt.getInteger("tickCounter");
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean("isWet", isWet);
        nbt.setInteger("tickCounter", tickCounter);
    }
}
