package vsharp.compiler.nativeimage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// One native image build: the classes to read, the entry point to start from, and where the
/// executable goes.
///
/// The classpath form is used even when the compiler produced a JAR, because a V# JAR carries no
/// `Main-Class` manifest attribute and the entry point is therefore always named explicitly.
///
/// @param classpath the emitted output to read, in search order
/// @param mainClass the binary name of the class whose `main` starts the image
/// @param image the executable to produce, without any platform extension
/// @param options additional launcher options placed before the classpath
public record NativeImageRequest(List<Path> classpath, String mainClass, Path image,
        List<String> options) {

    public NativeImageRequest {
        classpath = List.copyOf(Objects.requireNonNull(classpath, "classpath"));
        Objects.requireNonNull(mainClass, "mainClass");
        Objects.requireNonNull(image, "image");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        if (classpath.isEmpty()) {
            throw new IllegalArgumentException("a native image needs at least one classpath entry");
        }
        if (mainClass.isBlank()) {
            throw new IllegalArgumentException("a native image needs an entry point class");
        }
    }

    /// Creates a request with no extra launcher options.
    ///
    /// @param classpath the emitted output to read, in search order
    /// @param mainClass the binary name of the class whose `main` starts the image
    /// @param image the executable to produce
    /// @return the request
    public static NativeImageRequest of(List<Path> classpath, String mainClass, Path image) {
        return new NativeImageRequest(classpath, mainClass, image, List.of());
    }
}
