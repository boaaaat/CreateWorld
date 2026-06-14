package com.abhil.createworld;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(CreateWorld.MODID)
public final class CreateWorld {
    public static final String MODID = "createworld";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredBlock<Block> CREATIVE_PORTAL_FRAME = BLOCKS.registerSimpleBlock(
            "creative_portal_frame",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(4.0F, 1200.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.DEEPSLATE));

    public static final DeferredBlock<CreativePortalBlock> CREATIVE_PORTAL = BLOCKS.register(
            "creative_portal",
            () -> new CreativePortalBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .noCollission()
                    .noOcclusion()
                    .noLootTable()
                    .strength(-1.0F, 3600000.0F)
                    .lightLevel(state -> 11)
                    .sound(SoundType.GLASS)));

    public static final DeferredItem<BlockItem> CREATIVE_PORTAL_FRAME_ITEM = ITEMS.registerSimpleBlockItem(
            "creative_portal_frame",
            CREATIVE_PORTAL_FRAME);

    public static final DeferredItem<Item> CREATIVE_PORTAL_ACTIVATOR = ITEMS.register(
            "creative_portal_activator",
            () -> new CreativePortalActivatorItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATEWORLD_TAB = CREATIVE_TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.createworld"))
                    .withTabsBefore(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                    .icon(() -> CREATIVE_PORTAL_ACTIVATOR.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(CREATIVE_PORTAL_FRAME_ITEM.get());
                        output.accept(CREATIVE_PORTAL_ACTIVATOR.get());
                    })
                    .build());

    public CreateWorld(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);

        NeoForge.EVENT_BUS.register(new CreateWorldEvents());
        modContainer.registerConfig(ModConfig.Type.COMMON, CreateWorldConfig.SPEC);
    }
}
