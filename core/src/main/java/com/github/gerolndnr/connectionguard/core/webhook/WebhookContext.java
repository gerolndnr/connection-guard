package com.github.gerolndnr.connectionguard.core.webhook;

/** Internal notification-only snapshot; never part of Cloud or addon observations. */
public final class WebhookContext {
    public static final WebhookContext EMPTY = new WebhookContext(null,null,null,null);
    final String playerName, country, city, isp;
    private WebhookContext(String playerName,String country,String city,String isp) {
        this.playerName=scalar(playerName);this.country=scalar(country);this.city=scalar(city);this.isp=scalar(isp);
    }
    public WebhookContext withPlayerName(String name) { return new WebhookContext(name,country,city,isp); }
    public WebhookContext withLocation(String country,String city,String isp) { return new WebhookContext(playerName,country,city,isp); }
    private static String scalar(String value) {
        if(value==null)return null;
        String clean=CGWebHookRequest.safeText(value,200).replace('\n',' ').replace('\t',' ').trim();
        return clean.isEmpty() || clean.equalsIgnoreCase("Unknown") ? null : clean;
    }
}
