#pragma once

#include <string>
#include <utility>
#include <variant>

namespace uv::decode {

// Mirrors com.ultimatevideo.uveditor.engine.preview.PreviewErrorCode. Keep values in sync.
enum class Status : int {
    Ok = 0,
    InvalidArgument = 1,
    IoError = 2,
    UnsupportedFormat = 3,
    CodecError = 4,
    EglError = 5,
    GlError = 6,
    OutOfMemory = 7,
    NotFound = 8,
    InvalidState = 9,
};

struct Error {
    Status code;
    std::string message;
};

// Minimal expected-like result (C++20 has no std::expected).
template <typename T>
class Result {
public:
    Result(T value) : v_(std::move(value)) {}
    Result(Error error) : v_(std::move(error)) {}

    bool ok() const { return std::holds_alternative<T>(v_); }
    T& value() { return std::get<T>(v_); }
    const T& value() const { return std::get<T>(v_); }
    const Error& error() const { return std::get<Error>(v_); }

private:
    std::variant<T, Error> v_;
};

}  // namespace uv::decode
