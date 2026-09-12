package network.azusake.halo.core.runtime;

/** Session handshake policy. An integrated host always sends commands to its server. */
public final class ConnectionMode {
    private boolean remoteAuthority;
    public void hello() { remoteAuthority=true; }
    public void reset() { remoteAuthority=false; }
    public boolean remoteAuthority() { return remoteAuthority; }
    public boolean intercept(boolean integratedHost) { return !integratedHost && !remoteAuthority; }
}
