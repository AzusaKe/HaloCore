package network.azusake.halo.core.runtime;

/** Interaction policy. The adapter measures permissions, held items, targets and distances. */
public final class ScepterPolicy {
    public enum Failure { SESSION_EXPIRED, NO_PERMISSION, NOT_HOLDING, INVALID_TARGET, TOO_FAR, NO_HALO }
    public static Failure open(boolean permission,boolean holding,boolean validTarget) {
        if(!permission)return Failure.NO_PERMISSION;
        if(!holding)return Failure.NOT_HOLDING;
        return validTarget?null:Failure.INVALID_TARGET;
    }
    public static Failure select(boolean session,boolean permission,boolean holding,boolean validTarget) {
        return !session?Failure.SESSION_EXPIRED:open(permission,holding,validTarget);
    }
    public static Failure remove(boolean permission,boolean mainHand,boolean validTarget,boolean self,double distanceSquared,boolean wearing) {
        Failure failure=open(permission,mainHand,validTarget);if(failure!=null)return failure;
        if(!self && distanceSquared>36)return Failure.TOO_FAR;
        return wearing?null:Failure.NO_HALO;
    }
    public static boolean sessionValid(boolean playerAlive,boolean sameWorld,boolean targetAlive){return playerAlive&&sameWorld&&targetAlive;}
}
