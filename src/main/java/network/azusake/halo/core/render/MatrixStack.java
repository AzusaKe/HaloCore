package network.azusake.halo.core.render;

import java.util.ArrayDeque;
import org.joml.Matrix4f;

/** Internal scene transform stack, independent of the game's matrix stack. */
public final class MatrixStack {
    private final ArrayDeque<Matrix4f> stack = new ArrayDeque<>();
    public MatrixStack(float[] root) { stack.push(new Matrix4f().set(root)); }
    public void push() { stack.push(new Matrix4f(stack.peek())); }
    public void pop() { stack.pop(); }
    public void translate(double x,double y,double z) { stack.peek().translate((float)x,(float)y,(float)z); }
    public void scale(float x,float y,float z) { stack.peek().scale(x,y,z); }
    public Entry peek() { return new Entry(stack.peek()); }
    public record Entry(Matrix4f getPositionMatrix) {}
}
