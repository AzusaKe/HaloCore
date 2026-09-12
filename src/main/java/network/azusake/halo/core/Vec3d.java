package network.azusake.halo.core;

/** Immutable double-precision vector. Distances use world units (one block in Halo). */
public final class Vec3d {
    public static final Vec3d ZERO = new Vec3d(0, 0, 0);
    public final double x, y, z;
    public Vec3d(double x, double y, double z) { this.x=x; this.y=y; this.z=z; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public Vec3d add(Vec3d v) { return add(v.x,v.y,v.z); }
    public Vec3d add(double x,double y,double z) { return new Vec3d(this.x+x,this.y+y,this.z+z); }
    public Vec3d subtract(Vec3d v) { return add(-v.x,-v.y,-v.z); }
    public Vec3d multiply(double f) { return new Vec3d(x*f,y*f,z*f); }
    public double dotProduct(Vec3d v) { return x*v.x+y*v.y+z*v.z; }
    public Vec3d crossProduct(Vec3d v) { return new Vec3d(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x); }
    public double lengthSquared() { return x*x+y*y+z*z; }
    public double length() { return Math.sqrt(lengthSquared()); }
    public Vec3d normalize() { double n=length(); return n<1.0E-4 ? ZERO : multiply(1/n); }
    public double squaredDistanceTo(Vec3d v) { return subtract(v).lengthSquared(); }
    public double distanceTo(Vec3d v) { return Math.sqrt(squaredDistanceTo(v)); }
    public Vec3d lerp(Vec3d v,double t) { return add(v.subtract(this).multiply(t)); }
    @Override public boolean equals(Object o) { return o instanceof Vec3d v && Double.compare(x,v.x)==0 && Double.compare(y,v.y)==0 && Double.compare(z,v.z)==0; }
    @Override public int hashCode() { return java.util.Objects.hash(x,y,z); }
    @Override public String toString() { return "("+x+", "+y+", "+z+")"; }
}
