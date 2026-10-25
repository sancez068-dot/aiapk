#include <jni.h>
#include <cctype>
#include <string>
#include <algorithm>

namespace {
std::string from_jstring(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_orbit_agent_NativeCore_nativeSanitizeSlug(JNIEnv* env, jobject, jstring input) {
    const std::string raw = from_jstring(env, input);
    std::string output;
    output.reserve(std::min<std::size_t>(raw.size(), 36));
    bool dash = false;
    for (unsigned char c : raw) {
        if (std::isalnum(c) && c < 128) {
            output.push_back(static_cast<char>(std::tolower(c)));
            dash = false;
        } else if (!output.empty() && !dash) {
            output.push_back('-');
            dash = true;
        }
        if (output.size() >= 36) break;
    }
    while (!output.empty() && output.back() == '-') output.pop_back();
    if (output.empty()) output = "task";
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ai_orbit_agent_NativeCore_nativeIsRelativePathSafe(JNIEnv* env, jobject, jstring path) {
    if (path == nullptr) return JNI_FALSE;
    const jchar* chars = env->GetStringChars(path, nullptr);
    if (chars == nullptr) return JNI_FALSE;
    const jsize length = env->GetStringLength(path);
    for (jsize i = 0; i < length; ++i) {
        if (chars[i] == 0) {
            env->ReleaseStringChars(path, chars);
            return JNI_FALSE;
        }
    }
    env->ReleaseStringChars(path, chars);
    const std::string raw = from_jstring(env, path);
    std::string normalized = raw;
    std::replace(normalized.begin(), normalized.end(), '\\', '/');
    std::size_t start = 0;
    while (start <= normalized.size()) {
        const std::size_t end = normalized.find('/', start);
        const std::string part = normalized.substr(start, end == std::string::npos ? std::string::npos : end - start);
        if (part == "..") return JNI_FALSE;
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return JNI_TRUE;
}
