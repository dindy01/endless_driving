# The game uses no reflection. AdMob and UMP ship their own consumer rules.
# Views referenced from XML layouts:
-keep class com.endlessdrive.game.GameSurface { <init>(android.content.Context, android.util.AttributeSet); }
-keep class com.endlessdrive.game.HudView { <init>(android.content.Context, android.util.AttributeSet); }
