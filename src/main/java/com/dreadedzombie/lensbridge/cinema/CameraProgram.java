package com.dreadedzombie.lensbridge.cinema;

import com.dreadedzombie.lensbridge.DirectorCamera;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A live camera move, evaluated every frame (see DirectorCamera). {@code t} = seconds since the program
 * started (real time when watching / recording, exact frame time when rendered with capture_program).
 */
public interface CameraProgram {
    /** Return the shot for time t, or null when finished (non-looping programs). */
    @Nullable Shot eval(double t, float partialTick, @Nullable Shot previous);

    String describe();

    /** Total length in seconds (for capture_program default), or 0 if endless. */
    default double duration() {
        return 0;
    }

    static float[] aim(Vec3 from, Vec3 to) {
        return DirectorCamera.lookAt(from, to);
    }

    /** Ease in/out (smoothstep). */
    static double ease(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Map elapsed time to path progress for once / loop / pingpong. Null = finished. */
    static @Nullable Double progress(double t, double seconds, String mode, boolean smooth) {
        if (seconds <= 0) {
            return 0.0;
        }
        double u = t / seconds;
        switch (mode) {
            case "loop" -> u = u - Math.floor(u);
            case "pingpong" -> {
                double k = u % 2;
                u = k <= 1 ? k : 2 - k;
            }
            default -> {
                if (u > 1) {
                    return null;
                }
            }
        }
        return smooth ? ease(u) : u;
    }

    // ------------------------------------------------------------------ programs

    /** Stay where you are and track the target (with optional smoothing). */
    record Lock(Vec3 pos, Target target, float fov, float smoothing) implements CameraProgram {
        @Override
        public @Nullable Shot eval(double t, float pt, @Nullable Shot prev) {
            Vec3 aim = target.aim(pt);
            if (aim == null) {
                return prev;
            }
            float[] yp = aim(pos, aim);
            if (prev != null && smoothing > 0) {
                float k = 1 - smoothing;
                float dy = Mth.wrapDegrees(yp[0] - prev.yaw());
                yp[0] = prev.yaw() + dy * k;
                yp[1] = prev.pitch() + (yp[1] - prev.pitch()) * k;
            }
            return new Shot(pos, yp[0], yp[1], 0, fov);
        }

        @Override
        public String describe() {
            return "lock on " + target.describe();
        }
    }

    /** Circle the target (it can move); 360 views. speed in degrees/second, negative = clockwise. */
    record Orbit(Target target, double radius, double height, double speed, double startAngle, double sweep, float fov,
                 double lookHeight) implements CameraProgram {
        @Override
        public @Nullable Shot eval(double t, float pt, @Nullable Shot prev) {
            Vec3 c = target.aim(pt);
            if (c == null) {
                return prev;
            }
            double travelled = speed * t;
            if (sweep > 0 && Math.abs(travelled) > sweep) {
                return null;
            }
            double ang = Math.toRadians(startAngle + travelled);
            Vec3 cam = c.add(Math.sin(ang) * radius, height, Math.cos(ang) * radius);
            float[] yp = aim(cam, c.add(0, lookHeight, 0));
            return new Shot(cam, yp[0], yp[1], 0, fov);
        }

        @Override
        public double duration() {
            return sweep > 0 && speed != 0 ? sweep / Math.abs(speed) : 360 / Math.max(1e-3, Math.abs(speed));
        }

        @Override
        public String describe() {
            return "orbit " + target.describe() + " r=" + radius;
        }
    }

    /** Fixed position, sweep the view horizontally (360 panning). */
    record Pan(Vec3 pos, double startYaw, double speed, double sweep, float pitch, float fov) implements CameraProgram {
        @Override
        public @Nullable Shot eval(double t, float pt, @Nullable Shot prev) {
            double travelled = speed * t;
            if (sweep > 0 && Math.abs(travelled) > sweep) {
                return null;
            }
            return new Shot(pos, (float) (startYaw + travelled), pitch, 0, fov);
        }

        @Override
        public double duration() {
            return (sweep > 0 ? sweep : 360) / Math.max(1e-3, Math.abs(speed));
        }

        @Override
        public String describe() {
            return "pan from yaw " + startYaw;
        }
    }

    /** Ride a rail (spline path). Angles from the keys, or locked onto a target. */
    record Rail(CameraPath path, double seconds, String mode, boolean smooth, @Nullable Target lock) implements CameraProgram {
        @Override
        public @Nullable Shot eval(double t, float pt, @Nullable Shot prev) {
            Double u = progress(t, seconds, mode, smooth);
            if (u == null) {
                return null;
            }
            Shot s = path.sample(u);
            if (lock != null) {
                Vec3 aim = lock.aim(pt);
                if (aim != null) {
                    float[] yp = aim(s.pos(), aim);
                    return new Shot(s.pos(), yp[0], yp[1], s.roll(), s.fov());
                }
            }
            return s;
        }

        @Override
        public double duration() {
            return "once".equals(mode) ? seconds : seconds * ("pingpong".equals(mode) ? 2 : 1);
        }

        @Override
        public String describe() {
            return "rail " + path.keys().size() + " keys, " + seconds + "s " + mode + (lock != null ? ", locked on " + lock.describe() : "");
        }
    }

    /** Chase cam: sits behind/above the target relative to its facing, smoothly following. */
    record Follow(Target target, double distance, double height, double side, float smoothing, float fov) implements CameraProgram {
        @Override
        public @Nullable Shot eval(double t, float pt, @Nullable Shot prev) {
            Vec3 c = target.aim(pt);
            if (c == null) {
                return prev;
            }
            double yaw = Math.toRadians(target.facing(pt));
            Vec3 back = new Vec3(Math.sin(yaw), 0, -Math.cos(yaw));          // behind the target
            Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));      // the target's right-hand side
            Vec3 want = c.add(back.scale(distance)).add(right.scale(side)).add(0, height, 0);
            Vec3 cam = prev == null ? want : prev.pos().lerp(want, 1 - smoothing);
            float[] yp = aim(cam, c);
            return new Shot(cam, yp[0], yp[1], 0, fov);
        }

        @Override
        public String describe() {
            return "follow " + target.describe();
        }
    }

    /** Vertigo: move from `from` toward the target while widening the FOV so the subject keeps its size. */
    record DollyZoom(Vec3 from, Target target, double endDistance, double seconds, float startFov) implements CameraProgram {
        @Override
        public @Nullable Shot eval(double t, float pt, @Nullable Shot prev) {
            Vec3 c = target.aim(pt);
            if (c == null) {
                return prev;
            }
            if (t > seconds) {
                return null;
            }
            double u = ease(t / seconds);
            double d0 = from.distanceTo(c);
            double d = d0 + (endDistance - d0) * u;
            Vec3 dir = from.subtract(c).normalize();
            Vec3 cam = c.add(dir.scale(d));
            double half = Math.atan(Math.tan(Math.toRadians(startFov / 2)) * d0 / Math.max(0.1, d));
            float[] yp = aim(cam, c);
            return new Shot(cam, yp[0], yp[1], 0, (float) Math.toDegrees(half * 2));
        }

        @Override
        public double duration() {
            return seconds;
        }

        @Override
        public String describe() {
            return "dolly zoom on " + target.describe();
        }
    }
}
