# Native methods are resolved by JNI symbol name; keep class and native methods stable.
-keep class ai.orbit.agent.NativeCore { *; }

# Shizuku loads this UserService in a separate process from its class name.
-keep class ai.orbit.agent.OrbitShizukuUserService { *; }
-keep interface ai.orbit.agent.IOrbitShizukuService { *; }
-keep class ai.orbit.agent.IOrbitShizukuService$Stub { *; }
-keep class ai.orbit.agent.IOrbitShizukuService$Stub$Proxy { *; }

# Shizuku provider/IPC use reflective entry points.
-keep class rikka.shizuku.** { *; }
