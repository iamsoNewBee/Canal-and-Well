package com.catfish.canalandwell.block;

import java.util.ArrayList;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.catfish.canalandwell.CanalAndWell;
import com.catfish.canalandwell.Config;
import com.catfish.canalandwell.tileentity.TileEntityCanal;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 水渠方块 —— 定向状态机 + 旋转编码 + 封闭标志 + 材质变体。
 *
 * ============ Metadata 编码 (4 bit) ============
 * bit 0:    朝向  0=NS轴, 1=EW轴
 * bit 1-2:  形态  0=STRAIGHT, 1=T_LEFT, 2=T_RIGHT, 3=CROSS
 * bit 3:    封闭  0=正常, 1=封闭（不参与自动连接）
 *
 * LEFT / RIGHT 相对于朝向定义：
 *   NS轴: LEFT=W(-X), RIGHT=E(+X)
 *   EW轴: LEFT=N(-Z), RIGHT=S(+Z)
 *
 * 形状对照表：
 *   meta 0: NS+STRAIGHT  = 直管 NS         meta 8:  NS+STRAIGHT+CLOSED
 *   meta 1: EW+STRAIGHT  = 直管 EW         meta 9:  EW+STRAIGHT+CLOSED
 *   meta 2: NS+T_LEFT    = T_NW (NS主干+W分支, 三联通)
 *   meta 3: NS+T_RIGHT   = T_NE (NS主干+E分支, 三联通)
 *   meta 4: EW+T_LEFT    = T_EN (EW主干+N分支, 三联通)
 *   meta 5: EW+T_RIGHT   = T_ES (EW主干+S分支, 三联通)
 *   meta 6: NS+CROSS     = 四联通十字
 *   meta 7: (保留)
 *
 * ============ 材质变体 ============
 * STONE — 石质水渠 (canal* 纹理)
 * DIRT  — 土质水渠 (dirt* 纹理)
 * SAND  — 沙质水渠 (sand* 纹理)
 * 三种变体纹理统一放在 blocks/ 下，共享相同的状态机逻辑，仅材质不同。
 *
 * ============ 状态转换（相对方向, 消除重复代码）============
 * STRAIGHT: LEFT有水渠→T_LEFT, RIGHT有水渠→T_RIGHT
 * T_LEFT:   RIGHT有水渠→upgradeT(检查主干完整性),  LEFT无可见水渠→还原STRAIGHT
 * T_RIGHT:  LEFT有水渠→upgradeT(检查主干完整性),   RIGHT无可见水渠→还原STRAIGHT
 * CROSS:    任一侧无可见水渠→降级为对应的T型
 * CLOSED:   不参与任何自动转换，仅在主干轴向可见
 *
 * ============ 封闭水渠（Closed Canal）============
 * 潜行右键任意水渠 → 变为封闭直管（朝向由玩家面向决定）
 * 潜行右键封闭水渠 → 解除封闭（保持直管形状）
 * 封闭水渠仅在主干轴向（直接连接方向）上对邻居可见，
 * 垂直方向不可见，等效于原版 canalclose 的清除逻辑。
 *
 * 参考: data_dump.txt 原版 Canal 模组脚本
 */
public class BlockCanal extends BlockContainer {

    // ══════════════════════════════════════════════════════
    //  材质变体枚举
    // ══════════════════════════════════════════════════════

    public enum Variant {
        // 所有纹理统一放在 assets/canalandwell/textures/blocks/ 下，
        // 石质无前缀，土质 dirt 前缀，沙质 sand 前缀。
        STONE("canal", Material.rock, 1.5f, Block.soundTypeStone, "pickaxe", 0,
            "canalSide",
            "canalTop", "canalTopE", "canalTopC",
            "canalTopC1", "canalTopC2", "canalTopC3", "canalTopC4",
            "canalTopWet", "canalTopWetE", "canalTopWetC",
            "canalTopWetC1", "canalTopWetC2", "canalTopWetC3", "canalTopWetC4",
            "canalTopClose", "canalTopCloseE"),

        DIRT("canal_dirt", Material.ground, 1.0f, Block.soundTypeGravel, "shovel", 0,
            "dirtSide",
            "dirtTop", "dirtTopE", "dirtTopC",
            "dirtTopC1", "dirtTopC2", "dirtTopC3", "dirtTopC4",
            "dirtTopWet", "dirtTopWetE", "dirtTopWetC",
            "dirtTopWetC1", "dirtTopWetC2", "dirtTopWetC3", "dirtTopWetC4",
            "dirtTopClose", "dirtTopCloseE"),

        SAND("canal_sand", Material.sand, 0.8f, Block.soundTypeSand, "shovel", 0,
            "sandSide",
            "sandTop", "sandTopE", "sandTopC",
            "sandTopC1", "sandTopC2", "sandTopC3", "sandTopC4",
            "sandTopWet", "sandTopWetE", "sandTopWetC",
            "sandTopWetC1", "sandTopWetC2", "sandTopWetC3", "sandTopWetC4",
            "sandTopClose", "sandTopCloseE");

        /** 注册名 (不含 modid 前缀) */
        public final String blockName;
        public final Material material;
        public final float hardness;
        public final Block.SoundType sound;
        public final String harvestTool;
        public final int harvestLevel;

        // ── 纹理名（统一在 blocks/ 下，不含 modid 前缀）──
        public final String sideTex;
        // 干顶面: NS, EW, CROSS, C1, C2, C3, C4
        public final String dryNS, dryEW, dryC, dryC1, dryC2, dryC3, dryC4;
        // 湿顶面: NS, EW, CROSS, C1, C2, C3, C4
        public final String wetNS, wetEW, wetC, wetC1, wetC2, wetC3, wetC4;
        // 封闭顶面: NS, EW
        public final String closedNS, closedEW;

        Variant(String blockName, Material material, float hardness, Block.SoundType sound,
                String harvestTool, int harvestLevel,
                String sideTex,
                String dryNS, String dryEW, String dryC,
                String dryC1, String dryC2, String dryC3, String dryC4,
                String wetNS, String wetEW, String wetC,
                String wetC1, String wetC2, String wetC3, String wetC4,
                String closedNS, String closedEW) {
            this.blockName = blockName;
            this.material = material;
            this.hardness = hardness;
            this.sound = sound;
            this.harvestTool = harvestTool;
            this.harvestLevel = harvestLevel;
            this.sideTex = sideTex;
            this.dryNS = dryNS; this.dryEW = dryEW; this.dryC = dryC;
            this.dryC1 = dryC1; this.dryC2 = dryC2; this.dryC3 = dryC3; this.dryC4 = dryC4;
            this.wetNS = wetNS; this.wetEW = wetEW; this.wetC = wetC;
            this.wetC1 = wetC1; this.wetC2 = wetC2; this.wetC3 = wetC3; this.wetC4 = wetC4;
            this.closedNS = closedNS; this.closedEW = closedEW;
        }
    }

    // ─── 旋转常量 ───
    private static final int FACING_NS = 0;
    private static final int FACING_EW = 1;

    private static final int TYPE_STRAIGHT = 0;
    private static final int TYPE_T_LEFT   = 1;
    private static final int TYPE_T_RIGHT  = 2;
    private static final int TYPE_CROSS    = 3;

    // ─── 封闭标志 ───
    /** bit 3 (value 8): 封闭水渠标志。封闭水渠不参与自动连接状态转换。 */
    private static final int CLOSED_FLAG = 8;

    // RELATIVE[facing][side]: 0=LEFT, 1=RIGHT（垂直分支方向）
    private static final ForgeDirection[][] REL = {
        {ForgeDirection.WEST,  ForgeDirection.EAST},  // NS轴: LEFT=W, RIGHT=E
        {ForgeDirection.NORTH, ForgeDirection.SOUTH}, // EW轴: LEFT=N, RIGHT=S
    };

    // AXIS[facing]: 主干轴向（直接连接方向）
    private static final ForgeDirection[][] AXIS = {
        {ForgeDirection.NORTH, ForgeDirection.SOUTH}, // NS轴
        {ForgeDirection.EAST,  ForgeDirection.WEST},  // EW轴
    };

    private static final ForgeDirection[] HORIZONTALS = {
        ForgeDirection.NORTH, ForgeDirection.SOUTH,
        ForgeDirection.WEST,  ForgeDirection.EAST
    };

    // ══════════════════════════════════════════════════════
    //  当前变体
    // ══════════════════════════════════════════════════════

    public final Variant variant;

    // ══════════════════════════════════════════════════════
    //  纹理
    // ══════════════════════════════════════════════════════

    @SideOnly(Side.CLIENT)
    private IIcon iconSide;
    @SideOnly(Side.CLIENT)
    private IIcon iconTop, iconTopE, iconTopC;
    @SideOnly(Side.CLIENT)
    private IIcon iconTopC1, iconTopC2, iconTopC3, iconTopC4;
    @SideOnly(Side.CLIENT)
    private IIcon iconWet, iconWetE, iconWetC;
    @SideOnly(Side.CLIENT)
    private IIcon iconWetC1, iconWetC2, iconWetC3, iconWetC4;
    @SideOnly(Side.CLIENT)
    private IIcon iconTopClose, iconTopCloseE;

    // ══════════════════════════════════════════════════════
    //  构造
    // ══════════════════════════════════════════════════════

    public BlockCanal(Variant variant) {
        super(variant.material);
        this.variant = variant;
        setHardness(variant.hardness);
        setResistance(0.0f);
        setStepSound(variant.sound);
        setBlockName(variant.blockName);
        setBlockTextureName(CanalAndWell.MODID + ":" + variant.sideTex);
        setHarvestLevel(variant.harvestTool, variant.harvestLevel);
        setTickRandomly(true);
    }

    // ══════════════════════════════════════════════════════
    //  TileEntity
    // ══════════════════════════════════════════════════════

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityCanal();
    }

    // ══════════════════════════════════════════════════════
    //  放置
    // ══════════════════════════════════════════════════════

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        // 计算玩家面向的轴向
        int playerFacing = MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        int playerAxis = (playerFacing == 0 || playerFacing == 2) ? FACING_NS : FACING_EW;

        // 优先继承相邻直管水渠的朝向，但仅在玩家面向与邻居轴向平行时继承。
        // 若玩家面向垂直于邻居轴向，说明玩家意图放置垂直方向的 T 型连接，
        // 此时不继承邻居朝向，使用玩家自身的面向。
        int newFacing = -1;
        for (ForgeDirection dir : HORIZONTALS) {
            Block nb = world.getBlock(x + dir.offsetX, y + dir.offsetY, z + dir.offsetZ);
            if (nb instanceof BlockCanal) {
                int nMeta = world.getBlockMetadata(x + dir.offsetX, y + dir.offsetY, z + dir.offsetZ);
                if (getType(nMeta & 7) == TYPE_STRAIGHT && !isClosed(nMeta)) {
                    int neighborAxis = getFacing(nMeta & 7);
                    if (playerAxis == neighborAxis) {
                        // 玩家面向平行于邻居轴向 → 继承，保持并列同向
                        newFacing = neighborAxis;
                        break;
                    }
                    // 玩家面向垂直于邻居轴向 → 不继承，意图形成 T 型连接
                }
            }
        }
        if (newFacing < 0) {
            newFacing = playerAxis;
        }
        int meta = makeShape(newFacing, TYPE_STRAIGHT);
        world.setBlockMetadataWithNotify(x, y, z, meta, 2);
        // 在 onBlockPlacedBy 中执行状态转换，而不是 onBlockAdded。
        // 关键：在 1.7.10 中 onBlockAdded 先于 onBlockPlacedBy 执行，
        // 此时 metadata 仍为默认值 0 (NS直管)，若新方块紧邻 EW 直管放置，
        // 会错误地先形成 T 型连接，再被 onBlockPlacedBy 修正时已发生
        // 级联错误状态转换。将此逻辑移至此处保证状态机使用正确的初始朝向。
        notifyAndUpdate(world, x, y, z);
        checkAndWet(world, x, y, z);
    }

    @Override
    public void onBlockAdded(World world, int x, int y, int z) {
        super.onBlockAdded(world, x, y, z);
        // 状态转换已移至 onBlockPlacedBy，避免在 metadata 为默认值 0
        // 时触发错误级联。非玩家放置（世界生成等）依赖 updateTick 延迟收敛。
        checkAndWet(world, x, y, z);
    }

    // ══════════════════════════════════════════════════════
    //  邻居变化
    // ══════════════════════════════════════════════════════

    @Override
    public void onNeighborBlockChange(World world, int x, int y, int z, Block neighborBlock) {
        if (world.isRemote) return;
        Block fluid = getFluidBlock();
        if (neighborBlock instanceof BlockCanal || neighborBlock == Blocks.water
                || neighborBlock == fluid || neighborBlock == Blocks.air) {
            notifyAndUpdate(world, x, y, z);
        }
        if (neighborBlock == Blocks.water || neighborBlock == fluid) {
            checkAndWet(world, x, y, z);
        }
    }

    // ══════════════════════════════════════════════════════
    //  状态机核心 —— 相对方向消除重复
    // ══════════════════════════════════════════════════════

    /** 编码: type<<1 | facing | (closed ? 8 : 0) */
    private static int makeShape(int facing, int type) { return (type << 1) | facing; }
    private static int makeShape(int facing, int type, boolean closed) { return (type << 1) | facing | (closed ? CLOSED_FLAG : 0); }
    private static int getFacing(int shape)            { return shape & 1; }
    private static int getType(int shape)              { return (shape >> 1) & 3; }
    private static boolean isClosed(int meta)          { return (meta & CLOSED_FLAG) != 0; }

    private void notifyAndUpdate(World world, int x, int y, int z) {
        applyTransition(world, x, y, z);
        for (ForgeDirection dir : HORIZONTALS) {
            int nx = x + dir.offsetX, ny = y + dir.offsetY, nz = z + dir.offsetZ;
            if (world.getBlock(nx, ny, nz) instanceof BlockCanal) {
                ((BlockCanal) world.getBlock(nx, ny, nz)).applyTransition(world, nx, ny, nz);
            }
        }
    }

    /**
     * 根据当前形状 + 定向邻居执行状态转换。
     * 直线/T型使用相对方向（消除 NS/EW 重复），
     * CROSS 降级使用绝对方向映射（复现 data_dump）。
     */
    public void applyTransition(World world, int x, int y, int z) {
        int meta = world.getBlockMetadata(x, y, z);

        // ── 封闭水渠：不参与自动连接状态转换 ──
        if (isClosed(meta)) return;

        int shape = meta & 7;
        int facing = getFacing(shape);
        int type = getType(shape);

        ForgeDirection left  = REL[facing][0];
        ForgeDirection right = REL[facing][1];

        int newShape = shape;

        switch (type) {
            // ── 直管：检查左右是否有水渠 → 变 T ──
            case TYPE_STRAIGHT:
                if (hasCanal(world, x, y, z, left))
                    newShape = makeShape(facing, TYPE_T_LEFT);
                else if (hasCanal(world, x, y, z, right))
                    newShape = makeShape(facing, TYPE_T_RIGHT);
                break;

            // ── T_LEFT（左分支）：检查右侧→升级, 检查左侧→还原 ──
            case TYPE_T_LEFT:
                if (hasCanal(world, x, y, z, right))
                    newShape = upgradeT(facing, world, x, y, z);
                else if (!hasCanal(world, x, y, z, left))
                    newShape = makeShape(facing, TYPE_STRAIGHT);
                break;

            // ── T_RIGHT（右分支）：检查左侧→升级, 检查右侧→还原 ──
            case TYPE_T_RIGHT:
                if (hasCanal(world, x, y, z, left))
                    newShape = upgradeT(facing, world, x, y, z);
                else if (!hasCanal(world, x, y, z, right))
                    newShape = makeShape(facing, TYPE_STRAIGHT);
                break;

            // ── CROSS：任一侧无可见水渠 → 降级为 T ──
            case TYPE_CROSS:
                if (!hasCanal(world, x, y, z, ForgeDirection.WEST))
                    newShape = makeShape(FACING_NS, TYPE_T_RIGHT);  // 缺西→T_NE(NS+RIGHT)
                else if (!hasCanal(world, x, y, z, ForgeDirection.EAST))
                    newShape = makeShape(FACING_NS, TYPE_T_LEFT);   // 缺东→T_NW(NS+LEFT)
                else if (!hasCanal(world, x, y, z, ForgeDirection.NORTH))
                    newShape = makeShape(FACING_EW, TYPE_T_RIGHT);  // 缺北→T_ES(EW+RIGHT)
                else if (!hasCanal(world, x, y, z, ForgeDirection.SOUTH))
                    newShape = makeShape(FACING_EW, TYPE_T_LEFT);   // 缺南→T_EN(EW+LEFT)
                break;
        }

        if (newShape != shape) {
            world.setBlockMetadataWithNotify(x, y, z, newShape, 2);
        }
    }

    // ─── 方向查询辅助 ───
    /**
     * 检查指定方向是否有水渠连接。
     * 封闭水渠只在其轴向（直接连接方向）上可见，
     * 垂直方向视为不可见，等效于清除逻辑。
     * 同向直管并列放置（双方均为 STRAIGHT 且 facing 相同）
     * 不产生异向连通，各自保持独立。
     */
    private static boolean hasCanal(World world, int x, int y, int z, ForgeDirection dir) {
        int nx = x + dir.offsetX, ny = y + dir.offsetY, nz = z + dir.offsetZ;
        Block neighbor = world.getBlock(nx, ny, nz);
        if (!(neighbor instanceof BlockCanal)) return false;
        int neighborMeta = world.getBlockMetadata(nx, ny, nz);
        if (isClosed(neighborMeta)) {
            // 封闭水渠：仅在主干轴向（直接连接方向）可见
            int neighborFacing = getFacing(neighborMeta & 7);
            ForgeDirection approachDir = dir.getOpposite();
            if (approachDir != AXIS[neighborFacing][0] && approachDir != AXIS[neighborFacing][1]) {
                return false; // 垂直方向不可见，等效于清除
            }
        }
        // 同向直管并列：双方均为 STRAIGHT 且 facing 相同，不产生异向连通
        int currentMeta = world.getBlockMetadata(x, y, z);
        if (!isClosed(currentMeta) && !isClosed(neighborMeta)) {
            int curShape = currentMeta & 7;
            int neiShape = neighborMeta & 7;
            if (getType(curShape) == TYPE_STRAIGHT && getType(neiShape) == TYPE_STRAIGHT
                    && getFacing(curShape) == getFacing(neiShape)) {
                ForgeDirection approachDir = dir.getOpposite();
                int facing = getFacing(neiShape);
                if (approachDir != AXIS[facing][0] && approachDir != AXIS[facing][1]) {
                    return false; // 同向直管在分支方向不连通
                }
            }
        }
        return true;
    }

    /**
     * T型升级检查：左右两侧都有分支水渠时，检查主干两端是否完整。
     * 若主干仅一端有水渠 → 切换轴向形成新的三联通 T；
     * 若主干两端都有水渠 → 真正的四联通 CROSS；
     * 若主干两端皆空（均无连接）→ 两分支自成直管。
     */
    private static int upgradeT(int facing, World world, int x, int y, int z) {
        if (facing == FACING_EW) {
            boolean hasE = hasCanal(world, x, y, z, ForgeDirection.EAST);
            boolean hasW = hasCanal(world, x, y, z, ForgeDirection.WEST);
            if (hasE && hasW) {
                return makeShape(FACING_NS, TYPE_CROSS);           // N+S+E+W 真四联通 (meta 6)
            } else if (hasE) {
                return makeShape(FACING_NS, TYPE_T_RIGHT);          // N+S+E → T_NE
            } else if (hasW) {
                return makeShape(FACING_NS, TYPE_T_LEFT);           // N+S+W → T_NW
            } else {
                // 主干 E/W 两端皆空 → 两分支 N+S 自成 NS 直管
                boolean hasN = hasCanal(world, x, y, z, ForgeDirection.NORTH);
                boolean hasS = hasCanal(world, x, y, z, ForgeDirection.SOUTH);
                if (hasN && hasS) {
                    return makeShape(FACING_NS, TYPE_STRAIGHT);
                }
            }
        } else {
            boolean hasN = hasCanal(world, x, y, z, ForgeDirection.NORTH);
            boolean hasS = hasCanal(world, x, y, z, ForgeDirection.SOUTH);
            if (hasN && hasS) {
                return makeShape(FACING_NS, TYPE_CROSS);           // E+W+N+S 真四联通 (meta 6)
            } else if (hasN) {
                return makeShape(FACING_EW, TYPE_T_LEFT);           // E+W+N → T_EN
            } else if (hasS) {
                return makeShape(FACING_EW, TYPE_T_RIGHT);          // E+W+S → T_ES
            } else {
                // 主干 N/S 两端皆空 → 两分支 E+W 自成 EW 直管
                boolean hasE = hasCanal(world, x, y, z, ForgeDirection.EAST);
                boolean hasW = hasCanal(world, x, y, z, ForgeDirection.WEST);
                if (hasE && hasW) {
                    return makeShape(FACING_EW, TYPE_STRAIGHT);
                }
            }
        }
        // 无匹配规则 → 保持原形状不变
        return makeShape(facing, (facing == FACING_EW) ? TYPE_T_LEFT : TYPE_T_LEFT);
    }


    // ══════════════════════════════════════════════════════
    //  水源检测
    // ══════════════════════════════════════════════════════

    /**
     * 解析 Config.flowingFluidBlockName 为 Block 实例。
     * 若配置的名称无效，回退到原版水方块。
     */
    private static Block getFluidBlock() {
        Block configured = Block.getBlockFromName(Config.flowingFluidBlockName);
        if (configured != null) {
            return configured;
        }
        return Blocks.water;
    }

    /**
     * 检测水源并实时传播。
     * 当新方块加入已有水流的水渠系统时，立即触发湿润传播，
     * 无需等待 updateTick，保证"实时增添"的响应性。
     */
    private void checkAndWet(World world, int x, int y, int z) {
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileEntityCanal) {
            TileEntityCanal ct = (TileEntityCanal) te;
            if (!ct.isWet() && ct.detectWaterSource()) {
                ct.setWet(true);
                // 实时传播：立即将湿润状态传给相邻水渠并生成流水
                ct.propagateImmediate();
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  交互
    // ══════════════════════════════════════════════════════

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player,
                                     int side, float hitX, float hitY, float hitZ) {
        if (world.isRemote) return true;
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileEntityCanal)) return false;
        TileEntityCanal canalTe = (TileEntityCanal) te;

        // ── 潜行右键：切换封闭状态 ──
        if (player.isSneaking()) {
            player.swingItem();
            int meta = world.getBlockMetadata(x, y, z);
            if (isClosed(meta)) {
                // 已封闭 → 解除封闭，自动检测异向水渠形成连通结构
                int shape = meta & 7;
                world.setBlockMetadataWithNotify(x, y, z, shape, 2);
                // 两步收敛: STRAIGHT→T→CROSS (左右两侧均有异向水渠时)
                applyTransition(world, x, y, z);
                applyTransition(world, x, y, z);
            } else {
                // 未封闭 → 变为封闭直管
                // 十字水渠：朝向由玩家面向决定；其他形状：保持原主干方向
                int shape = meta & 7;
                int newFacing;
                if (getType(shape) == TYPE_CROSS) {
                    int facing = MathHelper.floor_double(player.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
                    newFacing = (facing == 0 || facing == 2) ? FACING_NS : FACING_EW;
                } else {
                    newFacing = getFacing(shape);
                }
                int newMeta = makeShape(newFacing, TYPE_STRAIGHT, true);
                world.setBlockMetadataWithNotify(x, y, z, newMeta, 2);
            }
            // 通知邻居重算连接
            for (ForgeDirection dir : HORIZONTALS) {
                int nx = x + dir.offsetX, ny = y + dir.offsetY, nz = z + dir.offsetZ;
                if (world.getBlock(nx, ny, nz) instanceof BlockCanal)
                    ((BlockCanal) world.getBlock(nx, ny, nz)).applyTransition(world, nx, ny, nz);
            }
            return true;
        }

        ItemStack held = player.getHeldItem();
        if (Config.enableBottleFill && held != null
                && held.getItem() == net.minecraft.init.Items.glass_bottle) {
            if (canalTe.isWet()) {
                player.swingItem();
                if (!player.capabilities.isCreativeMode) {
                    held.stackSize--;
                    if (held.stackSize <= 0)
                        player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                }
                ItemStack potion = new ItemStack(net.minecraft.init.Items.potionitem, 1, 0);
                if (!player.inventory.addItemStackToInventory(potion))
                    player.dropPlayerItemWithRandomChoice(potion, false);
                world.playSoundEffect(x + 0.5D, y + 0.5D, z + 0.5D, "random.splash", 0.5F, 1.0F);
                return true;
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════
    //  破坏
    // ══════════════════════════════════════════════════════

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileEntityCanal && ((TileEntityCanal) te).isWet()) {
            if (Config.cleanupFlowingWater) {
                Block fluid = getFluidBlock();
                for (ForgeDirection dir : HORIZONTALS) {
                    int nx = x + dir.offsetX, ny = y + dir.offsetY, nz = z + dir.offsetZ;
                    Block nb = world.getBlock(nx, ny, nz);
                    if ((nb == Blocks.water || nb == fluid)
                            && world.getBlockMetadata(nx, ny, nz) == Config.flowingFluidMeta)
                        world.setBlockToAir(nx, ny, nz);
                }
            }
        }
        super.breakBlock(world, x, y, z, block, meta);
        for (ForgeDirection dir : HORIZONTALS) {
            int nx = x + dir.offsetX, ny = y + dir.offsetY, nz = z + dir.offsetZ;
            Block nb = world.getBlock(nx, ny, nz);
            if (nb instanceof BlockCanal)
                ((BlockCanal) nb).applyTransition(world, nx, ny, nz);
        }
    }

    @Override
    public ArrayList<ItemStack> getDrops(World world, int x, int y, int z, int meta, int fortune) {
        ArrayList<ItemStack> drops = new ArrayList<>();
        drops.add(new ItemStack(this, 1, 0));
        return drops;
    }

    @Override public int damageDropped(int meta) { return 0; }

    // ══════════════════════════════════════════════════════
    //  方块更新
    // ══════════════════════════════════════════════════════

    @Override public int tickRate(World world) { return Config.tickRate; }

    @Override
    public void updateTick(World world, int x, int y, int z, Random random) {
        if (world.isRemote) return;
        applyTransition(world, x, y, z);
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileEntityCanal)
            ((TileEntityCanal) te).updateEntity();
    }

    // ══════════════════════════════════════════════════════
    //  渲染
    // ══════════════════════════════════════════════════════

    @Override public boolean renderAsNormalBlock() { return false; }
    @Override public boolean isOpaqueCube()       { return false; }
    @Override public int getRenderType()          { return 0; }

    // ══════════════════════════════════════════════════════
    //  纹理注册
    // ══════════════════════════════════════════════════════

    /** 拼接完整图标名: modid:texName */
    @SideOnly(Side.CLIENT)
    private String iconFull(String texName) {
        return CanalAndWell.MODID + ":" + texName;
    }

    @Override @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister reg) {
        Variant v = variant;
        iconSide   = reg.registerIcon(iconFull(v.sideTex));
        iconTop    = reg.registerIcon(iconFull(v.dryNS));
        iconTopE   = reg.registerIcon(iconFull(v.dryEW));
        iconTopC   = reg.registerIcon(iconFull(v.dryC));
        iconTopC1  = reg.registerIcon(iconFull(v.dryC1));
        iconTopC2  = reg.registerIcon(iconFull(v.dryC2));
        iconTopC3  = reg.registerIcon(iconFull(v.dryC3));
        iconTopC4  = reg.registerIcon(iconFull(v.dryC4));
        iconWet    = reg.registerIcon(iconFull(v.wetNS));
        iconWetE   = reg.registerIcon(iconFull(v.wetEW));
        iconWetC   = reg.registerIcon(iconFull(v.wetC));
        iconWetC1  = reg.registerIcon(iconFull(v.wetC1));
        iconWetC2  = reg.registerIcon(iconFull(v.wetC2));
        iconWetC3  = reg.registerIcon(iconFull(v.wetC3));
        iconWetC4  = reg.registerIcon(iconFull(v.wetC4));
        iconTopClose  = reg.registerIcon(iconFull(v.closedNS));
        iconTopCloseE = reg.registerIcon(iconFull(v.closedEW));
    }

    // ══════════════════════════════════════════════════════
    //  纹理获取 —— 旋转解码
    // ══════════════════════════════════════════════════════

    @Override @SideOnly(Side.CLIENT)
    public IIcon getIcon(IBlockAccess world, int x, int y, int z, int side) {
        int meta = world.getBlockMetadata(x, y, z);
        TileEntity te = world.getTileEntity(x, y, z);
        boolean wet = (te instanceof TileEntityCanal) && ((TileEntityCanal) te).isWet();
        return iconFor(side, meta, wet);
    }

    @Override @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        return iconFor(side, makeShape(FACING_NS, TYPE_STRAIGHT), false);
    }

    private IIcon iconFor(int side, int meta, boolean wet) {
        if (side == 0) return iconSide;
        if (side == 1) return topIcon(meta, wet);
        return iconSide;
    }

    /**
     * 顶面贴图 —— 朝向 + 形态 + 封闭标志 解码选择。
     *
     * 映射表（与原版一致）：
     *   NS+STRAIGHT → canalTop        NS+T_LEFT(NW) → canalTopC2
     *   EW+STRAIGHT → canalTopE       NS+T_RIGHT(NE) → canalTopC1
     *   CROSS       → canalTopC       EW+T_LEFT(EN)  → canalTopC3
     *                                 EW+T_RIGHT(ES) → canalTopC4
     *
     * 封闭时直管使用 canalTopClose / canalTopCloseE。
     */
    private IIcon topIcon(int meta, boolean wet) {
        boolean closed = isClosed(meta);
        int shape = meta & 7;
        int facing = getFacing(shape);
        int type   = getType(shape);

        switch (type) {
            case TYPE_STRAIGHT:
                if (closed) {
                    return (facing == FACING_NS) ? iconTopClose : iconTopCloseE;
                }
                return (facing == FACING_NS) ? (wet ? iconWet  : iconTop)
                                             : (wet ? iconWetE : iconTopE);
            case TYPE_T_LEFT:
                return (facing == FACING_NS) ? (wet ? iconWetC2 : iconTopC2)   // NW
                                             : (wet ? iconWetC3 : iconTopC3);  // EN
            case TYPE_T_RIGHT:
                return (facing == FACING_NS) ? (wet ? iconWetC1 : iconTopC1)   // NE
                                             : (wet ? iconWetC4 : iconTopC4);  // ES
            case TYPE_CROSS:
                return wet ? iconWetC : iconTopC;
            default:
                return wet ? iconWet : iconTop;
        }
    }

    @Override
    public boolean isToolEffective(String type, int metadata) {
        return variant.harvestTool.equals(type);
    }
}
