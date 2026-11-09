package com.dreadedzombie.lensbridge.cinema;

import net.minecraft.world.phys.Vec3;

/** One camera placement: position, yaw/pitch/roll (degrees) and FOV (degrees, <= 0 = player setting). */
public record Shot(Vec3 pos, float yaw, float pitch, float roll, float fov) {
}
