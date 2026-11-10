# Game code uses no reflection. AdMob / UMP ship their own consumer rules.
# Custom views inflated from XML (AAPT also generates keep rules for these).
-keep class com.endlessdrive.game.GameSurfaceView { <init>(...); }
-keep class com.endlessdrive.game.HudView { <init>(...); }
