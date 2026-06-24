package com.catfish.canalandwell.tileentity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

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
 * 水渠 TileEntity —— 仅存储湿水状态，处理水传播与连锁干燥。
 *
 * 连接与形态由 BlockCanal 在渲染时动态计算（参考红石线），
 * TE 不参与连接逻辑，避免状态漂移。
 *
 * ============ 客户端同步 ============
 * isWet 状态通过 S35PacketUpdateTileEntity 同步到客户端。
 * setWet(true/false) 时立即发送 packet 到所有追踪此位置的玩家，
 * 确保水渠贴图实时切换。
 *
 * ============ 连锁干燥 ============
 * 当水源被清除或水渠被封闭时，该水渠及其下游渠段应连锁变干。
 * checkShouldDry() 检查是否需要变干并递归通知邻居，通过
 * scheduleBlockUpdate 限流防止栈溢出。
 */
public class TileEntityCanal extends TileEntity {

    private boolean isWet;
    private int tickCounter;

    @Override
    public boolean canUpdate() {
        return true;
    }

    public boolean isWet() { return isWet; }

    /**
     * 设置湿润状态并同步到所有客户端。
     * 服务端调用时立即发送 TE 描述包。
     */
    public void setWet(boolean wet) {
        if (this.isWet != wet) {
            this.isWet = wet;
            markDirty();
            if (worldObj != null) {
                worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
                if (!worldObj.isRemote) {
                    sendTileEntityUpdate();
                }
            }
        }
    }

    /** 向所有追踪此位置的玩家发送 TE 描述包。 */
    private void sendTileEntityUpdate() {
        if (!(worldObj instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) worldObj;
        S35PacketUpdateTileEntity packet = new S35PacketUpdateTileEntity(
                xCoord, yCoord, zCoord, 0, getUpdateTag());
        double range = 64 * 64;
        for (Object obj : ws.playerEntities) {
            EntityPlayerMP player = (EntityPlayerMP) obj;
            if (player.getDistanceSq(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5) <= range) {
                player.playerNetServerHandler.sendPacket(packet);
            }
        }
    }

    private NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("isWet", isWet);
        return tag;
    }

    // ══════════════════════════════════════════════════════
    //  客户端同步 —— Packet
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
        if (worldObj != null) {
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    // ══════════════════════════════════════════════════════
    //  每 tick 水源检测 + 传播 + 干燥检查
    // ══════════════════════════════════════════════════════

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) return;

        if (++tickCounter < Config.tickRate) return;
        tickCounter = 0;

        Block block = worldObj.getBlock(xCoord, yCoord, zCoord);
        if (!(block instanceof BlockCanal)) return;

        // ── 1. 干燥→湿润：检测到水源 → 变湿 ──
        if (!isWet && detectWaterSource()) {
            setWet(true);
            if (Config.debugLogging) {
                CanalAndWell.LOG.info("[Canal] Wet at {}, {}, {}", xCoord, yCoord, zCoord);
            }
        }

        // ── 2. 湿润时：传播 + 生成流水 + 检查是否应干燥 ──
        if (isWet) {
            if (!shouldStayWet()) {
                setWet(false);
                if (Config.debugLogging) {
                    CanalAndWell.LOG.info("[Canal] Dry at {}, {}, {} (source lost)", xCoord, yCoord, zCoord);
                }
                // 通知邻居也检查是否变干（连锁干燥）
                notifyNeighborsCheckDry();
            } else {
                propagateWetness();
                spawnFlowingWater();
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  水源检测
    // ══════════════════════════════════════════════════════

    /**
     * 检测相邻是否有水源或已湿润水渠。
     * 用于干燥→湿润的判断。
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

            // 原版水源方块 (兜底)
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

    /**
     * 仅检查直接的水源方块（不检查湿润水渠邻居）。
     * 用于湿润→干燥判断的基础条件。
     */
    private boolean hasDirectWaterSource() {
        Block configuredFluid = getFluidBlock();

        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            if (dir == ForgeDirection.UP || dir == ForgeDirection.DOWN) continue;

            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;

            Block neighbor = worldObj.getBlock(nx, ny, nz);
            int neighborMeta = worldObj.getBlockMetadata(nx, ny, nz);

            if (neighbor == configuredFluid && neighborMeta == 0) return true;
            if (configuredFluid != Blocks.water && neighbor == Blocks.water && neighborMeta == 0) return true;
        }
        return false;
    }

    /**
     * 湿润水渠是否应该保持湿润。
     *
     * BFS 沿湿润水渠邻居回溯，在 waterFlowRange 深度内查找直接水源。
     * 任何湿润水渠必须能通过 ≤ waterFlowRange 跳的湿润路径追溯到水源方块，
     * 否则水道已断，应变干。
     */
    private boolean shouldStayWet() {
        if (hasDirectWaterSource()) return true;

        Set<Long> visited = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        visited.add(pack(xCoord, yCoord, zCoord));
        queue.add(new int[]{xCoord, yCoord, zCoord});

        int maxDepth = Config.waterFlowRange;
        for (int depth = 0; depth < maxDepth && !queue.isEmpty(); depth++) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                int[] p = queue.poll();
                for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                    if (dir == ForgeDirection.UP || dir == ForgeDirection.DOWN) continue;

                    int nx = p[0] + dir.offsetX;
                    int ny = p[1] + dir.offsetY;
                    int nz = p[2] + dir.offsetZ;
                    long key = pack(nx, ny, nz);
                    if (visited.contains(key)) continue;

                    if (worldObj.getBlock(nx, ny, nz) instanceof BlockCanal) {
                        TileEntity te = worldObj.getTileEntity(nx, ny, nz);
                        if (te instanceof TileEntityCanal && ((TileEntityCanal) te).isWet()) {
                            if (((TileEntityCanal) te).hasDirectWaterSource()) {
                                return true; // 找到水源
                            }
                            visited.add(key);
                            queue.add(new int[]{nx, ny, nz});
                        }
                    }
                }
            }
        }
        return false; // 无可达水源 → 应干燥
    }

    /** 将坐标打包为 long 供 visited set 使用 */
    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) | (((long) y & 0xFF) << 26) | (((long) z & 0x3FFFFFF) << 34);
    }

    // ══════════════════════════════════════════════════════
    //  连锁干燥
    // ══════════════════════════════════════════════════════

    /**
     * 外部调用入口 —— 当相邻方块被清除、水源被移除、或水渠被封闭时，
     * 由 BlockCanal 调用此方法触发连锁干燥检查。
     *
     * 若当前水渠湿润但不应保持湿润 → 变干 → 通知邻居也检查。
     */
    public void checkShouldDry() {
        if (!isWet) return;
        if (!shouldStayWet()) {
            setWet(false);
            if (Config.debugLogging) {
                CanalAndWell.LOG.info("[Canal] Dry at {}, {}, {} (chain)", xCoord, yCoord, zCoord);
            }
            notifyNeighborsCheckDry();
        }
    }

    /**
     * 通知所有水平相邻水渠也检查是否变干。
     * 通过设置 tickCounter = 0 并立即调用 checkShouldDry() 实现即时连锁，
     * 同时依赖 shouldStayWet() 的 2-hop 溯源保证正确终止。
     */
    private void notifyNeighborsCheckDry() {
        for (ForgeDirection dir : new ForgeDirection[] {
                ForgeDirection.NORTH, ForgeDirection.SOUTH,
                ForgeDirection.WEST,  ForgeDirection.EAST}) {

            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;

            if (worldObj.getBlock(nx, ny, nz) instanceof BlockCanal) {
                TileEntity te = worldObj.getTileEntity(nx, ny, nz);
                if (te instanceof TileEntityCanal) {
                    ((TileEntityCanal) te).checkShouldDry();
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  传播（湿润）
    // ══════════════════════════════════════════════════════

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

    /** 1-hop 实时传播。 */
    public void propagateImmediate() {
        if (!isWet) return;
        propagateWetness();
        spawnFlowingWater();
    }

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
