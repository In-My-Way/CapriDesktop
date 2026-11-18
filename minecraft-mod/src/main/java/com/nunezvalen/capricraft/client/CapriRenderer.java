package com.nunezvalen.capricraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nunezvalen.capricraft.companion.CapriEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

/** Capri as a player would look: the slim player model with her own skin, armor and what she holds */
public class CapriRenderer extends HumanoidMobRenderer<CapriEntity, AvatarRenderState, PlayerModel> {
	private static final Identifier SKIN = Identifier.fromNamespaceAndPath("capricraft", "textures/entity/capri.png");
	// The game draws every avatar-like state with its own player renderer, reading the texture from the skin
	private static final net.minecraft.world.entity.player.PlayerSkin CAPRI_SKIN = net.minecraft.world.entity.player.PlayerSkin.insecure(
		new net.minecraft.core.ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("capricraft", "entity/capri"), SKIN),
		null, null, net.minecraft.world.entity.player.PlayerModelType.SLIM);

	public CapriRenderer(EntityRendererProvider.Context context) {
		super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5F);
		this.addLayer(new HumanoidArmorLayer<>(
			this,
			ArmorModelSet.bake(ModelLayers.PLAYER_SLIM_ARMOR, context.getModelSet(), part -> new PlayerModel(part, true)),
			context.getEquipmentRenderer()
		));
	}

	@Override
	public AvatarRenderState createRenderState() {
		return new AvatarRenderState();
	}

	@Override
	public void extractRenderState(CapriEntity entity, AvatarRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.showHat = true;
		state.showJacket = true;
		state.showLeftPants = true;
		state.showRightPants = true;
		state.showLeftSleeve = true;
		state.showRightSleeve = true;
		state.showCape = false;
		state.isSpectator = false;
		state.skin = CAPRI_SKIN;
		state.id = entity.getId();
	}

	@Override
	protected HumanoidModel.ArmPose getArmPose(CapriEntity mob, HumanoidArm arm) {
		ItemStack held = mob.getItemHeldByArm(arm);
		return held.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
	}

	@Override
	public Identifier getTextureLocation(AvatarRenderState state) {
		return SKIN;
	}

	@Override
	protected void scale(AvatarRenderState state, PoseStack poseStack) {
		poseStack.scale(0.9375F, 0.9375F, 0.9375F);
	}
}
