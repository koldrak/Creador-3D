import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.stage.Stage;
import javafx.scene.transform.Rotate;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * BlockBuilder3D - modo creativo sencillo al estilo Minecraft.
 * - WASD/QE: mover la cámara en el mundo.
 * - Ratón: mirar alrededor.
 * - Click derecho: colocar un bloque sobre la superficie apuntada por la mirilla.
 */
public class BlockBuilder3D extends Application {

    // ======= Config cámara / controles =======
    private final double CAM_SPEED = 6.0;       // unidades por segundo (traslación)
    private final double MOUSE_SENS = 0.15;     // grados por píxel
    private final double PITCH_MIN = -89, PITCH_MAX = -5;
    private final double YAW_MIN = -179, YAW_MAX = 179;

    // ======= Estructura escena =======
    private SubScene subScene3D;
    private Group worldRoot = new Group();
    private PerspectiveCamera camera;

    // Cámara en ejes
    private double camX = 0, camY = 6, camZ = 20;
    private double yaw = 0, pitch = -30; // grados

    // Estado input
    private final Set<KeyCode> keys = new HashSet<>();
    private double lastMouseX = -1, lastMouseY = -1;

    // Raycast epsilon
    private final double EPS = 1e-6;

    // Suelo
    private Box ground;

    // Tipos de bloque (plantillas)
    private static class BlockType {
        final String name;
        final double sizeX, sizeY, sizeZ;

        BlockType(String name, double sizeX, double sizeY, double sizeZ) {
            this.name = name;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }

        @Override public String toString() {
            return name + "  [" + fmt(sizeX) + " x " + fmt(sizeY) + " x " + fmt(sizeZ) + "]";
        }
        private String fmt(double d) { return (Math.round(d * 10.0) / 10.0) + ""; }
    }

    // Bloque instanciado en el mundo
    private static class BlockInstance {
        final BlockType type;
        final Box node;
        double x, y, z;

        BlockInstance(BlockType type, Box node, double x, double y, double z) {
            this.type = type; this.node = node; this.x = x; this.y = y; this.z = z;
        }

        void applyTransform() {
            node.setTranslateX(x);
            node.setTranslateY(y);
            node.setTranslateZ(z);
        }

        AABB aabb() {
            double hx = node.getWidth() / 2.0;
            double hy = node.getHeight() / 2.0;
            double hz = node.getDepth() / 2.0;
            return new AABB(x - hx, y - hy, z - hz, x + hx, y + hy, z + hz);
        }
    }

    // AABB para raycast
    private static class AABB {
        final double minX, minY, minZ, maxX, maxY, maxZ;
        AABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
    }

    // Mundo
    private final List<BlockInstance> blocks = new ArrayList<>();

    // Tipo de bloque actual
    private final BlockType currentType = new BlockType("Cubo 1m", 1, 1, 1);

    @Override
    public void start(Stage stage) {
        // ----- 3D SubScene + mirilla -----
        camera = new PerspectiveCamera(true);
        camera.setNearClip(0.05);
        camera.setFarClip(1000);

        buildWorld();

        subScene3D = new SubScene(worldRoot, 1280, 720, true, SceneAntialiasing.BALANCED);
        subScene3D.setCamera(camera);

        // Fondo y capa de mirilla
        Pane crosshair = crosshairNode();
        StackPane root = new StackPane(subScene3D, crosshair);
        root.setStyle("-fx-background-color: #202020;");

        Scene scene = new Scene(root, 1400, 800, true);
        stage.setTitle("BlockBuilder3D - MVP");
        stage.setScene(scene);
        stage.show();

        // Eventos teclado / mouse
        setupInputHandlers(scene);

        // Bucle principal
        AnimationTimer timer = new AnimationTimer() {
            long lastNs = -1;
            @Override public void handle(long now) {
                if (lastNs < 0) { lastNs = now; return; }
                double dt = (now - lastNs) / 1_000_000_000.0;
                lastNs = now;
                tick(dt);
            }
        };
        timer.start();

        // Colocar cámara al inicio
        applyCameraTransform();
    }

    // ----- Construcción del mundo (suelo + luz) -----
    private void buildWorld() {
        // Luz ambiente
        AmbientLight amb = new AmbientLight(Color.color(0.55, 0.55, 0.55));
        // Luz direccional suave
        PointLight sun = new PointLight(Color.WHITE);
        sun.setTranslateX(60);
        sun.setTranslateY(-80);
        sun.setTranslateZ(-60);

        // Suelo grande
        ground = new Box(2000, 1, 2000);
        ground.setCullFace(CullFace.NONE);
        PhongMaterial m = new PhongMaterial(Color.DARKSLATEGRAY);
        ground.setMaterial(m);
        ground.setTranslateY(0); // superficie superior en y=0.5, pero lo dejamos plano visualmente

        worldRoot.getChildren().addAll(ground, amb, sun);

        // Cuadrícula visual simple (opcional: pequeñas cajas finas como líneas)
        buildGridLines(100, 2.0, Color.color(1,1,1,0.08));
    }

    private void buildGridLines(int half, double step, Color color) {
        PhongMaterial mat = new PhongMaterial(color);
        for (int i = -half; i <= half; i++) {
            // líneas paralelas eje X (variando Z)
            Box lineZ = new Box(half * 2 * step, 0.05, 0.02);
            lineZ.setMaterial(mat);
            lineZ.setTranslateX(0);
            lineZ.setTranslateY(0.5);
            lineZ.setTranslateZ(i * step);
            // líneas paralelas eje Z (variando X)
            Box lineX = new Box(0.02, 0.05, half * 2 * step);
            lineX.setMaterial(mat);
            lineX.setTranslateX(i * step);
            lineX.setTranslateY(0.5);
            lineX.setTranslateZ(0);
            worldRoot.getChildren().addAll(lineZ, lineX);
        }
    }

    // ----- Mirilla (cruz al centro) -----
    private Pane crosshairNode() {
        Pane p = new Pane();
        p.setPickOnBounds(false);
        Region v = new Region(); v.setPrefSize(2, 18); v.setStyle("-fx-background-color: white;");
        Region h = new Region(); h.setPrefSize(18, 2); h.setStyle("-fx-background-color: white;");
        StackPane cross = new StackPane(new StackPane(h), new StackPane(v));
        cross.setMouseTransparent(true);
        cross.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane wrapper = new StackPane(cross);
        wrapper.setMouseTransparent(true);
        return wrapper;
    }

    // ----- Input -----
    private void setupInputHandlers(Scene scene) {
        scene.setOnKeyPressed(e -> {
            keys.add(e.getCode());
        });
        scene.setOnKeyReleased(e -> {
            keys.remove(e.getCode());
        });

        scene.setOnMouseMoved(e -> {
            if (lastMouseX < 0) { lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY(); return; }
            double dx = e.getSceneX() - lastMouseX;
            double dy = e.getSceneY() - lastMouseY;
            lastMouseX = e.getSceneX();
            lastMouseY = e.getSceneY();
            yaw += dx * MOUSE_SENS;
            pitch -= dy * MOUSE_SENS;
            if (pitch < PITCH_MIN) pitch = PITCH_MIN;
            if (pitch > PITCH_MAX) pitch = PITCH_MAX;
            if (yaw < YAW_MIN) yaw = YAW_MIN;
            if (yaw > YAW_MAX) yaw = YAW_MAX;
            applyCameraTransform();
        });
        scene.setOnMouseExited(e -> { lastMouseX = -1; lastMouseY = -1; });

        scene.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                // Click derecho: colocar bloque donde apunta la mirilla
                placeBlockFromCrosshair();
            }
        });
    }

    // ----- Bucle de actualización -----
    private void tick(double dt) {
        updateCamera(dt);
    }

    private void updateCamera(double dt) {
        double speed = CAM_SPEED;

        // Dirección forward en el plano XZ a partir del yaw
        double yawRad = Math.toRadians(yaw);
        double forwardX = Math.sin(yawRad);
        double forwardZ = -Math.cos(yawRad);
        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);

        double vx = 0, vy = 0, vz = 0;

        if (keys.contains(KeyCode.W)) { vx += forwardX; vz += forwardZ; }
        if (keys.contains(KeyCode.S)) { vx -= forwardX; vz -= forwardZ; }
        if (keys.contains(KeyCode.D)) { vx += rightX;   vz += rightZ;   }
        if (keys.contains(KeyCode.A)) { vx -= rightX;   vz -= rightZ;   }
        if (keys.contains(KeyCode.Q)) { vy -= 1; }
        if (keys.contains(KeyCode.E)) { vy += 1; }

        // Normalizar para no ir más rápido en diagonal
        double len = Math.sqrt(vx*vx + vy*vy + vz*vz);
        if (len > EPS) {
            vx /= len; vy /= len; vz /= len;
            camX += vx * speed * dt;
            camY += vy * speed * dt;
            camZ += vz * speed * dt;
            applyCameraTransform();
        }
    }


    private void applyCameraTransform() {
        camera.setTranslateX(camX);
        camera.setTranslateY(camY);
        camera.setTranslateZ(camZ);

        camera.getTransforms().setAll(
                new RotateY(yaw),
                new RotateX(pitch)
        );
    }

    // Rotaciones utilitarias (locales) para la cámara
    private static class RotateY extends Rotate {
        RotateY(double angle) { super(angle, Rotate.Y_AXIS); }
    }
    private static class RotateX extends Rotate {
        RotateX(double angle) { super(angle, Rotate.X_AXIS); }
    }

    // ----- Colocar bloque con raycast desde la mirilla -----
    private void placeBlockFromCrosshair() {
        // Ray desde la cámara hacia adelante (según yaw/pitch)
        Vec3 origin = new Vec3(camX, camY, camZ);
        Vec3 dir = forwardFromAngles(Math.toRadians(yaw), Math.toRadians(pitch));
        Ray ray = new Ray(origin, dir);

        // Recolectar candidatos (bloques + suelo como AABB)
        List<Hit> hits = new ArrayList<>();

        // Suelo: lo tratamos como caja muy delgada centrada en y=0 (ancha)
        AABB groundAabb = new AABB(-1000, -1, -1000, 1000, 0.5, 1000);
        Hit hGround = intersect(ray, groundAabb);
        if (hGround != null) hits.add(hGround);

        for (BlockInstance bi : blocks) {
            Hit h = intersect(ray, bi.aabb());
            if (h != null) hits.add(h);
        }

        if (hits.isEmpty()) return;

        // Elegir el hit más cercano con t > 0
        hits.sort(Comparator.comparingDouble(h -> h.t));
        Hit best = null;
        for (Hit h : hits) {
            if (h.t > EPS) { best = h; break; }
        }
        if (best == null) return;

        // Tipo de bloque actual
        BlockType sel = currentType;

        // Crear bloque
        Box box = new Box(sel.sizeX, sel.sizeY, sel.sizeZ);
        box.setMaterial(randomMaterial());
        box.setCullFace(CullFace.BACK);

        // Poner el nuevo centro ADYACENTE a la cara golpeada: punto + normal*(halfSize)
        Vec3 half = new Vec3(sel.sizeX/2.0, sel.sizeY/2.0, sel.sizeZ/2.0);
        Vec3 placeCenter = best.point.add(best.normal.mul(half.dotAbs(best.normal))).add(best.normal.mul(EPS*10));

        BlockInstance bi = new BlockInstance(sel, box, placeCenter.x, placeCenter.y, placeCenter.z);
        bi.applyTransform();
        blocks.add(bi);
        worldRoot.getChildren().add(box);

    }

    private PhongMaterial randomMaterial() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Color c = Color.hsb(r.nextDouble(360), 0.55, 0.95);
        return new PhongMaterial(c);
    }

    // ----- Raycast AABB (método de los "slabs") -----
    private static class Ray {
        final Vec3 o, d;
        Ray(Vec3 o, Vec3 d) { this.o = o; this.d = d; }
    }
    private static class Hit {
        final double t;
        final Vec3 point;
        final Vec3 normal;
        Hit(double t, Vec3 point, Vec3 normal) { this.t = t; this.point = point; this.normal = normal; }
    }

    private Hit intersect(Ray ray, AABB box) {
        double tmin = Double.NEGATIVE_INFINITY, tmax = Double.POSITIVE_INFINITY;
        Vec3 normal = new Vec3(0,0,0);

        // X
        if (Math.abs(ray.d.x) < EPS) {
            if (ray.o.x < box.minX || ray.o.x > box.maxX) return null;
        } else {
            double inv = 1.0 / ray.d.x;
            double t1 = (box.minX - ray.o.x) * inv;
            double t2 = (box.maxX - ray.o.x) * inv;
            double tEnterX = Math.min(t1, t2);
            double tExitX  = Math.max(t1, t2);
            if (tEnterX > tmin) {
                tmin = tEnterX;
                normal = new Vec3((t1 > t2) ? 1 : -1, 0, 0);
            }
            tmax = Math.min(tmax, tExitX);
            if (tmin > tmax) return null;
        }

        // Y
        if (Math.abs(ray.d.y) < EPS) {
            if (ray.o.y < box.minY || ray.o.y > box.maxY) return null;
        } else {
            double inv = 1.0 / ray.d.y;
            double t1 = (box.minY - ray.o.y) * inv;
            double t2 = (box.maxY - ray.o.y) * inv;
            double tEnterY = Math.min(t1, t2);
            double tExitY  = Math.max(t1, t2);
            if (tEnterY > tmin) {
                tmin = tEnterY;
                normal = new Vec3(0, (t1 > t2) ? 1 : -1, 0);
            }
            tmax = Math.min(tmax, tExitY);
            if (tmin > tmax) return null;
        }

        // Z
        if (Math.abs(ray.d.z) < EPS) {
            if (ray.o.z < box.minZ || ray.o.z > box.maxZ) return null;
        } else {
            double inv = 1.0 / ray.d.z;
            double t1 = (box.minZ - ray.o.z) * inv;
            double t2 = (box.maxZ - ray.o.z) * inv;
            double tEnterZ = Math.min(t1, t2);
            double tExitZ  = Math.max(t1, t2);
            if (tEnterZ > tmin) {
                tmin = tEnterZ;
                normal = new Vec3(0, 0, (t1 > t2) ? 1 : -1);
            }
            tmax = Math.min(tmax, tExitZ);
            if (tmin > tmax) return null;
        }

        if (tmin < 0) return null; // detrás de la cámara

        Vec3 p = ray.o.add(ray.d.mul(tmin));
        return new Hit(tmin, p, normal);
    }

    // ----- util vector -----
    private static class Vec3 {
        final double x, y, z;
        Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        Vec3 add(Vec3 o) { return new Vec3(x+o.x, y+o.y, z+o.z); }
        Vec3 mul(double s) { return new Vec3(x*s, y*s, z*s); }
        double dotAbs(Vec3 n) { return Math.abs(x*n.x) + Math.abs(y*n.y) + Math.abs(z*n.z); }
    }

    private Vec3 forwardFromAngles(double yawRad, double pitchRad) {
        double fx = Math.sin(yawRad) * Math.cos(pitchRad);
        double fy = Math.sin(pitchRad) * 1.0;
        double fz = -Math.cos(yawRad) * Math.cos(pitchRad);
        // Normaliza
        double len = Math.sqrt(fx*fx + fy*fy + fz*fz);
        return new Vec3(fx/len, fy/len, fz/len);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
