# Game code uses no reflection. AdMob / UMP ship their own consumer rules.
# Keep custom view constructors used from XML layouts.
-keep class com.endlessdrive.game.GameView { <init>(...); }
