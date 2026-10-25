-keep class io.github.gauthierhuguenin.atvwake.** { *; }
-dontobfuscate
# R8 optimisation breaks verification of some BouncyCastle classes on Android 11.
-dontoptimize
-dontwarn **
