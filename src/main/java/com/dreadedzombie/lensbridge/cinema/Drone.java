package com.dreadedzombie.lensbridge.cinema;

import com.dreadedzombie.lensbridge.DirectorCamera;
import com.dreadedzombie.lensbridge.Game;
import java.util.List;
import java.util.Locale;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A physics drone camera: inertia, acceleration/speed limits, yaw-rate limit, banking on turns, a stabilised
 * gimbal (or none, in FPV), hover wobble and ground clearance. Autonomous modes generate a target position and
 * aim; the physics flies there, so motion always looks like real drone footage.
 * Time comes from the program clock, so capture_program renders it frame-exactly.
 */
public final class Drone implements CameraProgram {
    public enum Mode { HOVER, MISSION, ORBIT, FOLLOW, DRONIE, REVEAL, TOPDOWN, FPV, PILOT }

    /** Stick inputs for PILOT mode (-1..1). Written by the keyboard pilot or the drone_control tool. */
    public static volatile float stickForward;
    public static volatile float stickRight;
    public static volatile float stickUp;
    public static volatile float stickYaw;
    public static volatile float stickGimbal;

    public record Waypoint(Vec3 pos, double hold) {
    }

    // configuration
    final Mode mode;
    final @Nullable Target target;
    final List<Waypoint> waypoints;
    final boolean loop;
    final double maxSpeed;      // blocks/s
    final double maxAccel;      // blocks/s^2
    final double yawRate;       // deg/s
    final double bank;          // degrees of roll per block/s^2 of lateral acceleration
    final double wobble;        // 0..1
    final double clearance;     // min blocks above ground
    final double distance;
    final double height;
    final double radius;
    final double speed;         // deg/s for orbit / topdown spin
    final double seconds;       // for dronie / reveal
    final float fov;
    final Shot start;

    // state
    private Vec3 pos;
    private Vec3 vel = Vec3.ZERO;
    private Vec3 lastAccel = Vec3.ZERO;
    private float yaw;
    private float gimbal;
    private float roll;
    private double lastT = -1;
    private int wp;
    private double holdLeft = -1;
    private double alt;

    public Drone(Mode mode, Shot start, @Nullable Target target, List<Waypoint> waypoints, boolean loop, double maxSpeed,
                 double maxAccel, double yawRate, double bank, double wobble, double clearance, double distance, double height,
                 double radius, double speed, double seconds, float fov) {
        this.mode = mode;
        this.start = start;
        this.target = target;
        this.waypoints = waypoints;
        this.loop = loop;
        this.maxSpeed = maxSpeed;
        this.maxAccel = maxAccel;
        this.yawRate = yawRate;
        this.bank = bank;
        this.wobble = wobble;
        this.clearance = clearance;
        this.distance = distance;
        this.height = height;
        this.radius = radius;
        this.speed = speed;
        this.seconds = seconds;
        this.fov = fov;
        reset();
    }

    private void reset() {
        this.pos = start.pos();
        this.vel = Vec3.ZERO;
        this.lastAccel = Vec3.ZERO;
        this.yaw = start.yaw();
        this.gimbal = Mth.clamp(start.pitch(), -30, 90);
        this.roll = 0;
        this.lastT = 0;
        this.wp = 0;
        this.holdLeft = -1;
    }

    @Override
    public @Nullable Shot eval(double t, float pt, @Nullable Shot prev) {
        double dt = t - lastT;
        if (dt < 0 || dt > 1.0) {
            reset();       // restarted (e.g. capture_program from t=0) - fly the same shot again
            dt = 0;
        }
        lastT = t;
        int steps = Math.max(1, (int) Math.ceil(dt / (1.0 / 120)));
        for (int i = 0; i < steps; i++) {
            step(t - dt + dt * (i + 1) / steps, dt / steps, pt);
        }
        if (mode == Mode.DRONIE || mode == Mode.REVEAL) {
            if (t > seconds + 1.5) {
                return null;
            }
        }
        if (mode == Mode.MISSION && !loop && wp >= waypoints.size()) {
            return null;
        }
        // hover wobble: small, slower when the drone is moving
        double w = wobble / (1 + vel.length());
        Vec3 shake = new Vec3(Math.sin(t * 1.3) * 0.04 + Math.sin(t * 3.1) * 0.015, Math.sin(t * 1.7 + 1) * 0.05,
                Math.cos(t * 1.1) * 0.04).scale(w);
        boolean fpv = mode == Mode.FPV;
        float shownRoll = fpv ? roll : roll * 0.12F;                     // gimbal removes most of the bank
        // FPV: no gimbal - the camera dips with the drone's forward tilt as it speeds up
        float camPitch = fpv ? Mth.clamp(gimbal * 0.4F + (float) Math.min(20, vel.length() * 1.2), -30, 60) : gimbal;
        float yawShake = (float) (Math.sin(t * 0.9) * 0.4 * w);
        return new Shot(pos.add(shake), yaw + yawShake, camPitch, shownRoll, fov);
    }

    private void step(double t, double dt, float pt) {
        if (dt <= 0) {
            return;
        }
        Vec3 desired = pos;
        Vec3 look = null;
        float wantGimbal = gimbal;
        Vec3 desiredVel = null;
        Vec3 tgt = target != null ? target.aim(pt) : null;
        switch (mode) {
            case HOVER -> {
                desired = start.pos();
                look = tgt;
            }
            case MISSION, FPV -> {
                if (!waypoints.isEmpty()) {
                    int idx = loop ? wp % waypoints.size() : Math.min(wp, waypoints.size() - 1);
                    Waypoint w = waypoints.get(idx);
                    desired = w.pos();
                    if (pos.distanceTo(w.pos()) < (mode == Mode.FPV ? 2.0 : 0.6)) {
                        if (holdLeft < 0) {
                            holdLeft = w.hold();
                        }
                        holdLeft -= dt;
                        if (holdLeft <= 0) {
                            wp++;
                            holdLeft = -1;
                        }
                    }
                }
                look = tgt;
            }
            case ORBIT -> {
                if (tgt != null) {
                    double ang = Math.toRadians(start.yaw() + 180 + speed * t);
                    desired = tgt.add(Math.sin(ang) * radius, height, Math.cos(ang) * radius);
                    look = tgt;
                }
            }
            case FOLLOW -> {
                if (tgt != null) {
                    double fy = Math.toRadians(target.facing(pt));
                    Vec3 back = new Vec3(Math.sin(fy), 0, -Math.cos(fy));
                    desired = tgt.add(back.scale(distance)).add(0, height, 0);
                    look = tgt;
                }
            }
            case DRONIE -> {
                if (tgt != null) {
                    Vec3 away = new Vec3(start.pos().x - tgt.x, 0, start.pos().z - tgt.z);
                    away = away.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : away.normalize();
                    double u = CameraProgram.ease(t / seconds);
                    double d0 = Math.max(1.5, Math.hypot(start.pos().x - tgt.x, start.pos().z - tgt.z));
                    desired = tgt.add(away.scale(d0 + distance * u)).add(0, (start.pos().y - tgt.y) + height * u, 0);
                    look = tgt;
                }
            }
            case REVEAL -> {
                double u = CameraProgram.ease(t / seconds);
                Vec3 fwd = Vec3.directionFromRotation(0, start.yaw());
                desired = start.pos().add(0, height * u, 0).add(fwd.scale(distance * u));
                if (tgt != null) {
                    look = tgt;
                } else {
                    wantGimbal = (float) (start.pitch() + 35 * u);
                }
            }
            case TOPDOWN -> {
                Vec3 c = tgt != null ? tgt : start.pos();
                desired = new Vec3(c.x, c.y + height, c.z);
                wantGimbal = 90;
            }
            case PILOT -> {
                Vec3 fwd = Vec3.directionFromRotation(0, yaw);
                Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
                desiredVel = fwd.scale(stickForward).add(right.scale(stickRight)).add(0, stickUp, 0).scale(maxSpeed);
                wantGimbal = Mth.clamp(gimbal + stickGimbal * 60 * (float) dt, -30, 90);
                yaw += stickYaw * (float) (yawRate * dt);
            }
        }

        // ---- translational physics: arrive at the desired point, limited speed and acceleration
        if (desiredVel == null) {
            Vec3 diff = desired.subtract(pos);
            double dist = diff.length();
            double vmax = Math.min(maxSpeed, Math.sqrt(2 * maxAccel * Math.max(0, dist - 0.05)) * 0.9);   // brake in time
            desiredVel = dist < 1e-4 ? Vec3.ZERO : diff.scale(vmax / dist);
        }
        Vec3 accel = desiredVel.subtract(vel).scale(3.0);
        if (accel.length() > maxAccel) {
            accel = accel.normalize().scale(maxAccel);
        }
        lastAccel = accel;
        vel = vel.add(accel.scale(dt));
        pos = pos.add(vel.scale(dt));

        // ---- ground clearance (and don't fly into terrain)
        var level = Game.mc().level;
        if (level != null) {
            double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(pos.x), Mth.floor(pos.z));
            alt = pos.y - ground;
            if (pos.y < ground + clearance) {
                pos = new Vec3(pos.x, ground + clearance, pos.z);
                vel = new Vec3(vel.x, Math.max(0, vel.y), vel.z);
            }
        }

        // ---- heading and gimbal
        if (mode != Mode.PILOT) {
            float wantYaw = yaw;
            if (look != null) {
                float[] yp = DirectorCamera.lookAt(pos, look);
                wantYaw = yp[0];
                wantGimbal = yp[1];
            } else if (horizontalSpeed() > 0.6) {
                wantYaw = (float) Math.toDegrees(Math.atan2(-vel.x, vel.z));
            }
            if (mode == Mode.TOPDOWN) {
                wantYaw = (float) (start.yaw() + speed * t);
            }
            float dy = Mth.wrapDegrees(wantYaw - yaw);
            float maxTurn = (float) (yawRate * dt);
            yaw += Mth.clamp(dy * (float) Math.min(1, dt * 6), -maxTurn, maxTurn);
        }
        gimbal += (Mth.clamp(wantGimbal, -30, 90) - gimbal) * (float) Math.min(1, dt * 8);    // stabilised gimbal

        // ---- bank into lateral acceleration
        Vec3 right = new Vec3(-Math.cos(Math.toRadians(yaw)), 0, -Math.sin(Math.toRadians(yaw)));
        double lateral = accel.dot(right);
        float limit = mode == Mode.FPV ? 50 : 20;
        float wantRoll = (float) Mth.clamp(lateral * bank * (mode == Mode.FPV ? 2.5 : 1), -limit, limit);
        roll += (wantRoll - roll) * (float) Math.min(1, dt * 5);
    }

    private double horizontalSpeed() {
        return Math.hypot(vel.x, vel.z);
    }

    @Override
    public double duration() {
        return switch (mode) {
            case DRONIE, REVEAL -> seconds + 1.5;
            case ORBIT -> 360 / Math.max(1e-3, Math.abs(speed));
            default -> 0;
        };
    }

    @Override
    public String describe() {
        return "drone " + mode.name().toLowerCase(Locale.ROOT) + (target != null ? " on " + target.describe() : "");
    }

    /** Direct mouse-look for a human pilot (degrees). */
    public void nudge(float dYaw, float dPitch) {
        this.yaw += dYaw;
        this.gimbal = Mth.clamp(this.gimbal + dPitch, -30, 90);
    }

    public Mode mode() {
        return this.mode;
    }

    public String telemetry() {
        return String.format(Locale.ROOT, "%s  ALT %.1f  SPD %.1f  YAW %.0f  GIMBAL %.0f", mode, alt, vel.length(),
                Mth.wrapDegrees(yaw), gimbal);
    }
}
