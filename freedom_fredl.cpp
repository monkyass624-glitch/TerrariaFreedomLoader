#include "freedom_fredl.h"

#include <zlib.h>

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace fs = std::filesystem;

namespace {
constexpr uint64_t kMaxArchiveBytes = 128ull * 1024ull * 1024ull;
constexpr uint64_t kMaxTotalExpanded = 96ull * 1024ull * 1024ull;
constexpr uint64_t kMaxEntryExpanded = 32ull * 1024ull * 1024ull;
constexpr size_t kMaxEntries = 512;
constexpr size_t kMaxManifestBytes = 256u * 1024u;

struct ZipEntry {
    std::string name;
    uint16_t flags = 0;
    uint16_t method = 0;
    uint32_t crc = 0;
    uint32_t compressed_size = 0;
    uint32_t expanded_size = 0;
    uint32_t local_offset = 0;
    bool directory = false;
};

struct Archive {
    std::vector<uint8_t> bytes;
    std::vector<ZipEntry> entries;
};

struct Json {
    enum class Kind { Null, Boolean, Number, String, Array, Object };
    Kind kind = Kind::Null;
    bool boolean = false;
    double number = 0.0;
    std::string string;
    std::vector<Json> array;
    std::map<std::string, Json> object;

    const Json* get(const char* key) const {
        if (kind != Kind::Object) return nullptr;
        auto it = object.find(key);
        return it == object.end() ? nullptr : &it->second;
    }
};

void set_error(std::string* error, const std::string& message) {
    if (error) *error = message;
}

uint16_t u16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (static_cast<uint16_t>(p[1]) << 8));
}

uint32_t u32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

bool range_ok(size_t offset, size_t length, size_t total) {
    return offset <= total && length <= total - offset;
}

bool read_file(const std::string& path, std::vector<uint8_t>& out, std::string* error) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) {
        set_error(error, "cannot open package");
        return false;
    }
    const std::streamoff length = file.tellg();
    if (length < 22 || static_cast<uint64_t>(length) > kMaxArchiveBytes) {
        set_error(error, "archive size is outside allowed limits");
        return false;
    }
    out.resize(static_cast<size_t>(length));
    file.seekg(0, std::ios::beg);
    if (!file.read(reinterpret_cast<char*>(out.data()), length)) {
        set_error(error, "failed to read complete archive");
        return false;
    }
    return true;
}

bool safe_relative_path(const std::string& path, bool directory) {
    if (path.empty() || path.size() > 1024 || path[0] == '/' ||
        path.find('\\') != std::string::npos ||
        path.find(':') != std::string::npos ||
        path.find('\0') != std::string::npos) return false;
    if (path.size() >= 2 && std::isalpha(static_cast<unsigned char>(path[0])) &&
        path[1] == ':') return false;

    size_t start = 0;
    for (size_t i = 0; i <= path.size(); ++i) {
        if (i != path.size() && path[i] != '/') continue;
        const std::string part = path.substr(start, i - start);
        if (part.empty()) {
            if (directory && i == path.size() && i > 0 && path[i - 1] == '/') break;
            return false;
        }
        if (part == "." || part == "..") return false;
        start = i + 1;
    }
    return true;
}

std::string normalized_zip_name(const std::string& name, bool directory) {
    if (directory && !name.empty() && name.back() == '/') return name.substr(0, name.size() - 1);
    return name;
}

bool parse_zip(Archive& archive, std::string* error) {
    const auto& bytes = archive.bytes;
    if (bytes.size() < 22) {
        set_error(error, "not a ZIP archive");
        return false;
    }

    const size_t lower = bytes.size() > 65557 ? bytes.size() - 65557 : 0;
    size_t eocd = std::numeric_limits<size_t>::max();
    for (size_t p = bytes.size() - 22;; --p) {
        if (u32(bytes.data() + p) == 0x06054b50u) {
            const uint16_t comment = u16(bytes.data() + p + 20);
            if (range_ok(p, 22u + comment, bytes.size()) && p + 22u + comment == bytes.size()) {
                eocd = p;
                break;
            }
        }
        if (p == lower) break;
    }
    if (eocd == std::numeric_limits<size_t>::max()) {
        set_error(error, "ZIP end-of-central-directory record not found");
        return false;
    }

    const uint16_t disk = u16(bytes.data() + eocd + 4);
    const uint16_t central_disk = u16(bytes.data() + eocd + 6);
    const uint16_t disk_entries = u16(bytes.data() + eocd + 8);
    const uint16_t total_entries = u16(bytes.data() + eocd + 10);
    const uint32_t central_size = u32(bytes.data() + eocd + 12);
    const uint32_t central_offset = u32(bytes.data() + eocd + 16);

    if (disk || central_disk || disk_entries != total_entries) {
        set_error(error, "multi-disk ZIP archives are not supported");
        return false;
    }
    if (total_entries == 0xffffu || central_size == 0xffffffffu || central_offset == 0xffffffffu) {
        set_error(error, "ZIP64 archives are not supported by FREDL v1");
        return false;
    }
    if (total_entries == 0 || total_entries > kMaxEntries ||
        !range_ok(central_offset, central_size, eocd)) {
        set_error(error, "invalid ZIP central directory bounds or entry count");
        return false;
    }

    size_t cursor = central_offset;
    const size_t central_end = static_cast<size_t>(central_offset) + central_size;
    uint64_t total_expanded = 0;
    std::set<std::string> names;
    archive.entries.clear();
    archive.entries.reserve(total_entries);

    for (uint16_t i = 0; i < total_entries; ++i) {
        if (!range_ok(cursor, 46, central_end) || u32(bytes.data() + cursor) != 0x02014b50u) {
            set_error(error, "malformed ZIP central directory entry");
            return false;
        }
        const uint16_t made_by = u16(bytes.data() + cursor + 4);
        ZipEntry entry;
        entry.flags = u16(bytes.data() + cursor + 8);
        entry.method = u16(bytes.data() + cursor + 10);
        entry.crc = u32(bytes.data() + cursor + 16);
        entry.compressed_size = u32(bytes.data() + cursor + 20);
        entry.expanded_size = u32(bytes.data() + cursor + 24);
        const uint16_t name_length = u16(bytes.data() + cursor + 28);
        const uint16_t extra_length = u16(bytes.data() + cursor + 30);
        const uint16_t comment_length = u16(bytes.data() + cursor + 32);
        const uint16_t start_disk = u16(bytes.data() + cursor + 34);
        const uint32_t external_attributes = u32(bytes.data() + cursor + 38);
        entry.local_offset = u32(bytes.data() + cursor + 42);
        const size_t record_length = 46u + name_length + extra_length + comment_length;
        if (!range_ok(cursor, record_length, central_end) || start_disk != 0 ||
            name_length == 0 || entry.expanded_size > kMaxEntryExpanded) {
            set_error(error, "invalid ZIP entry metadata or unsupported split entry");
            return false;
        }

        entry.name.assign(reinterpret_cast<const char*>(bytes.data() + cursor + 46), name_length);
        entry.directory = entry.name.back() == '/';
        if (!safe_relative_path(entry.name, entry.directory)) {
            set_error(error, "unsafe archive path: " + entry.name);
            return false;
        }
        const std::string normalized = normalized_zip_name(entry.name, entry.directory);
        if (!names.insert(normalized).second) {
            set_error(error, "duplicate archive path: " + normalized);
            return false;
        }

        // Unix-created symbolic links are forbidden, even when their names look harmless.
        const uint32_t unix_mode = external_attributes >> 16;
        if ((made_by >> 8) == 3 && (unix_mode & 0170000u) == 0120000u) {
            set_error(error, "symbolic links are not permitted in FREDL packages");
            return false;
        }
        if ((entry.flags & 0x0001u) != 0) {
            set_error(error, "encrypted ZIP entries are not supported");
            return false;
        }
        if (entry.method != 0 && entry.method != 8) {
            set_error(error, "unsupported ZIP compression method");
            return false;
        }
        if (entry.method == 0 && entry.compressed_size != entry.expanded_size) {
            set_error(error, "stored ZIP entry has inconsistent sizes");
            return false;
        }
        if (entry.compressed_size > kMaxArchiveBytes ||
            !range_ok(entry.local_offset, 30, bytes.size()) ||
            u32(bytes.data() + entry.local_offset) != 0x04034b50u) {
            set_error(error, "invalid ZIP local file header");
            return false;
        }
        const size_t local = entry.local_offset;
        const uint16_t local_flags = u16(bytes.data() + local + 6);
        const uint16_t local_method = u16(bytes.data() + local + 8);
        const uint16_t local_name_len = u16(bytes.data() + local + 26);
        const uint16_t local_extra_len = u16(bytes.data() + local + 28);
        if (local_flags != entry.flags || local_method != entry.method ||
            !range_ok(local + 30, static_cast<size_t>(local_name_len) + local_extra_len, bytes.size()) ||
            local_name_len != name_length ||
            std::memcmp(bytes.data() + local + 30, entry.name.data(), name_length) != 0) {
            set_error(error, "ZIP local and central headers do not match");
            return false;
        }
        const size_t data_offset = local + 30u + local_name_len + local_extra_len;
        if (!range_ok(data_offset, entry.compressed_size, bytes.size())) {
            set_error(error, "ZIP entry data exceeds archive bounds");
            return false;
        }
        total_expanded += entry.expanded_size;
        if (total_expanded > kMaxTotalExpanded) {
            set_error(error, "package expands beyond the FREDL size limit");
            return false;
        }

        archive.entries.push_back(std::move(entry));
        cursor += record_length;
    }
    if (cursor != central_end) {
        set_error(error, "unexpected trailing data in ZIP central directory");
        return false;
    }
    return true;
}

bool expand_entry(const Archive& archive, const ZipEntry& entry,
                  std::vector<uint8_t>& output, std::string* error) {
    const auto& bytes = archive.bytes;
    const size_t local = entry.local_offset;
    const uint16_t name_len = u16(bytes.data() + local + 26);
    const uint16_t extra_len = u16(bytes.data() + local + 28);
    const size_t data_offset = local + 30u + name_len + extra_len;
    const uint8_t* compressed = bytes.data() + data_offset;
    output.assign(entry.expanded_size, 0);

    if (entry.method == 0) {
        if (entry.expanded_size) std::memcpy(output.data(), compressed, entry.expanded_size);
    } else {
        z_stream stream{};
        stream.next_in = const_cast<Bytef*>(reinterpret_cast<const Bytef*>(compressed));
        stream.avail_in = entry.compressed_size;
        uint8_t empty_output = 0;
        stream.next_out = output.empty() ? &empty_output : reinterpret_cast<Bytef*>(output.data());
        stream.avail_out = output.empty() ? 1u : static_cast<uInt>(output.size());
        if (inflateInit2(&stream, -MAX_WBITS) != Z_OK) {
            set_error(error, "could not initialize ZIP decompressor");
            return false;
        }
        const int result = inflate(&stream, Z_FINISH);
        const bool okay = result == Z_STREAM_END &&
                          stream.total_out == entry.expanded_size &&
                          stream.total_in == entry.compressed_size;
        inflateEnd(&stream);
        if (!okay) {
            set_error(error, "ZIP decompression failed or produced an unexpected size");
            return false;
        }
    }

    const uLong actual_crc = crc32(0L,
        output.empty() ? Z_NULL : reinterpret_cast<const Bytef*>(output.data()),
        static_cast<uInt>(output.size()));
    if (static_cast<uint32_t>(actual_crc) != entry.crc) {
        set_error(error, "ZIP CRC32 mismatch: " + entry.name);
        return false;
    }
    return true;
}

void append_utf8(std::string& out, uint32_t cp) {
    if (cp <= 0x7f) out.push_back(static_cast<char>(cp));
    else if (cp <= 0x7ff) {
        out.push_back(static_cast<char>(0xc0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else if (cp <= 0xffff) {
        out.push_back(static_cast<char>(0xe0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else {
        out.push_back(static_cast<char>(0xf0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    }
}

class JsonParser {
public:
    explicit JsonParser(const std::string& input) : input_(input) {}

    bool parse(Json& value, std::string* error) {
        if (!parse_value(value, 0)) {
            set_error(error, "invalid manifest JSON at byte " + std::to_string(position_));
            return false;
        }
        skip_space();
        if (position_ != input_.size()) {
            set_error(error, "trailing data after manifest JSON");
            return false;
        }
        return true;
    }

private:
    const std::string& input_;
    size_t position_ = 0;

    void skip_space() {
        while (position_ < input_.size() &&
               (input_[position_] == ' ' || input_[position_] == '\n' ||
                input_[position_] == '\r' || input_[position_] == '\t')) ++position_;
    }

    bool consume(char c) {
        skip_space();
        if (position_ >= input_.size() || input_[position_] != c) return false;
        ++position_;
        return true;
    }

    bool hex4(uint32_t& value) {
        if (position_ + 4 > input_.size()) return false;
        value = 0;
        for (int i = 0; i < 4; ++i) {
            const char c = input_[position_++];
            value <<= 4;
            if (c >= '0' && c <= '9') value |= static_cast<uint32_t>(c - '0');
            else if (c >= 'a' && c <= 'f') value |= static_cast<uint32_t>(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') value |= static_cast<uint32_t>(c - 'A' + 10);
            else return false;
        }
        return true;
    }

    bool parse_string(std::string& out) {
        skip_space();
        if (position_ >= input_.size() || input_[position_++] != '"') return false;
        out.clear();
        while (position_ < input_.size()) {
            const unsigned char c = static_cast<unsigned char>(input_[position_++]);
            if (c == '"') return true;
            if (c < 0x20) return false;
            if (c != '\\') { out.push_back(static_cast<char>(c)); continue; }
            if (position_ >= input_.size()) return false;
            const char escaped = input_[position_++];
            switch (escaped) {
                case '"': out.push_back('"'); break;
                case '\\': out.push_back('\\'); break;
                case '/': out.push_back('/'); break;
                case 'b': out.push_back('\b'); break;
                case 'f': out.push_back('\f'); break;
                case 'n': out.push_back('\n'); break;
                case 'r': out.push_back('\r'); break;
                case 't': out.push_back('\t'); break;
                case 'u': {
                    uint32_t cp = 0;
                    if (!hex4(cp)) return false;
                    if (cp >= 0xd800 && cp <= 0xdbff) {
                        if (position_ + 2 > input_.size() ||
                            input_[position_] != '\\' || input_[position_ + 1] != 'u') return false;
                        position_ += 2;
                        uint32_t low = 0;
                        if (!hex4(low) || low < 0xdc00 || low > 0xdfff) return false;
                        cp = 0x10000 + ((cp - 0xd800) << 10) + (low - 0xdc00);
                    } else if (cp >= 0xdc00 && cp <= 0xdfff) return false;
                    append_utf8(out, cp);
                    break;
                }
                default: return false;
            }
        }
        return false;
    }

    bool parse_number(Json& value) {
        skip_space();
        const size_t start = position_;
        if (position_ < input_.size() && input_[position_] == '-') ++position_;
        if (position_ >= input_.size()) return false;
        if (input_[position_] == '0') ++position_;
        else {
            if (!std::isdigit(static_cast<unsigned char>(input_[position_]))) return false;
            while (position_ < input_.size() &&
                   std::isdigit(static_cast<unsigned char>(input_[position_]))) ++position_;
        }
        if (position_ < input_.size() && input_[position_] == '.') {
            ++position_;
            const size_t fraction = position_;
            while (position_ < input_.size() &&
                   std::isdigit(static_cast<unsigned char>(input_[position_]))) ++position_;
            if (fraction == position_) return false;
        }
        if (position_ < input_.size() && (input_[position_] == 'e' || input_[position_] == 'E')) {
            ++position_;
            if (position_ < input_.size() && (input_[position_] == '+' || input_[position_] == '-')) ++position_;
            const size_t exponent = position_;
            while (position_ < input_.size() &&
                   std::isdigit(static_cast<unsigned char>(input_[position_]))) ++position_;
            if (exponent == position_) return false;
        }
        const std::string token = input_.substr(start, position_ - start);
        char* end = nullptr;
        value.number = std::strtod(token.c_str(), &end);
        if (!end || *end != '\0') return false;
        value.kind = Json::Kind::Number;
        return true;
    }

    bool parse_value(Json& value, unsigned depth) {
        if (depth > 32) return false;
        skip_space();
        if (position_ >= input_.size()) return false;
        const char c = input_[position_];
        if (c == '"') {
            value.kind = Json::Kind::String;
            return parse_string(value.string);
        }
        if (c == '{') {
            ++position_;
            value.kind = Json::Kind::Object;
            skip_space();
            if (consume('}')) return true;
            for (;;) {
                std::string key;
                if (!parse_string(key) || !consume(':')) return false;
                Json child;
                if (!parse_value(child, depth + 1)) return false;
                if (!value.object.emplace(key, std::move(child)).second) return false;
                if (consume('}')) return true;
                if (!consume(',')) return false;
            }
        }
        if (c == '[') {
            ++position_;
            value.kind = Json::Kind::Array;
            skip_space();
            if (consume(']')) return true;
            for (;;) {
                Json child;
                if (!parse_value(child, depth + 1)) return false;
                value.array.push_back(std::move(child));
                if (value.array.size() > 4096) return false;
                if (consume(']')) return true;
                if (!consume(',')) return false;
            }
        }
        if (input_.compare(position_, 4, "true") == 0) {
            position_ += 4; value.kind = Json::Kind::Boolean; value.boolean = true; return true;
        }
        if (input_.compare(position_, 5, "false") == 0) {
            position_ += 5; value.kind = Json::Kind::Boolean; value.boolean = false; return true;
        }
        if (input_.compare(position_, 4, "null") == 0) {
            position_ += 4; value.kind = Json::Kind::Null; return true;
        }
        return parse_number(value);
    }
};

bool string_field(const Json& object, const char* key, std::string& out, bool required,
                  std::string* error) {
    const Json* value = object.get(key);
    if (!value) {
        if (!required) { out.clear(); return true; }
        set_error(error, std::string("manifest is missing field: ") + key);
        return false;
    }
    if (value->kind != Json::Kind::String) {
        set_error(error, std::string("manifest field must be a string: ") + key);
        return false;
    }
    out = value->string;
    return true;
}

bool valid_id(const std::string& id) {
    if (id.size() < 3 || id.size() > 128 || id.front() == '.' || id.back() == '.') return false;
    bool has_dot = false;
    bool previous_separator = false;
    for (char c : id) {
        if (c == '.') {
            if (previous_separator) return false;
            has_dot = true;
            previous_separator = true;
        } else if (c == '-') {
            if (previous_separator) return false;
            previous_separator = true;
        } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
            previous_separator = false;
        } else return false;
    }
    return has_dot && !previous_separator;
}

bool valid_semver(const std::string& value) {
    size_t pos = 0;
    for (int part = 0; part < 3; ++part) {
        const size_t start = pos;
        while (pos < value.size() && std::isdigit(static_cast<unsigned char>(value[pos]))) ++pos;
        if (start == pos || (pos - start > 1 && value[start] == '0')) return false;
        if (part < 2) {
            if (pos >= value.size() || value[pos++] != '.') return false;
        }
    }
    if (pos < value.size() && value[pos] == '-') {
        ++pos;
        const size_t start = pos;
        while (pos < value.size() &&
               (std::isalnum(static_cast<unsigned char>(value[pos])) || value[pos] == '.' || value[pos] == '-')) ++pos;
        if (start == pos || value[start] == '.' || value[pos - 1] == '.') return false;
    }
    if (pos < value.size() && value[pos] == '+') {
        ++pos;
        const size_t start = pos;
        while (pos < value.size() &&
               (std::isalnum(static_cast<unsigned char>(value[pos])) || value[pos] == '.' || value[pos] == '-')) ++pos;
        if (start == pos || value[start] == '.' || value[pos - 1] == '.') return false;
    }
    return pos == value.size();
}

struct Manifest {
    std::string id;
    std::string name;
    std::string version;
    std::string entrypoint;
    std::string raw_json;
};

bool validate_manifest(const Json& root, const Archive& archive,
                       const std::string& game_version, const std::string& platform,
                       Manifest& manifest, std::string* error) {
    if (root.kind != Json::Kind::Object) {
        set_error(error, "manifest root must be a JSON object");
        return false;
    }
    std::string format;
    if (!string_field(root, "format", format, true, error) || format != "FREDL") {
        if (error && error->empty()) set_error(error, "manifest format must be FREDL");
        return false;
    }
    const Json* format_version = root.get("formatVersion");
    if (!format_version || format_version->kind != Json::Kind::Number ||
        format_version->number != 1.0) {
        set_error(error, "unsupported FREDL formatVersion (expected 1)");
        return false;
    }
    if (!string_field(root, "id", manifest.id, true, error) || !valid_id(manifest.id)) {
        set_error(error, "manifest id must be a lowercase reverse-DNS identifier");
        return false;
    }
    if (!string_field(root, "name", manifest.name, true, error) ||
        manifest.name.empty() || manifest.name.size() > 128) {
        set_error(error, "manifest name is empty or too long");
        return false;
    }
    if (!string_field(root, "version", manifest.version, true, error) ||
        !valid_semver(manifest.version)) {
        set_error(error, "manifest version must use semantic versioning");
        return false;
    }

    const Json* game = root.get("game");
    if (!game || game->kind != Json::Kind::Object) {
        set_error(error, "manifest game must be an object");
        return false;
    }
    std::string game_id;
    if (!string_field(*game, "id", game_id, true, error) || game_id != "terraria-mobile") {
        set_error(error, "unsupported game id (expected terraria-mobile)");
        return false;
    }
    const Json* versions = game->get("versions");
    if (!versions || versions->kind != Json::Kind::Array || versions->array.empty()) {
        set_error(error, "game.versions must be a non-empty array");
        return false;
    }
    bool game_supported = false;
    for (const Json& item : versions->array) {
        if (item.kind != Json::Kind::String || item.string.empty() || item.string.size() > 32) {
            set_error(error, "game.versions contains an invalid version");
            return false;
        }
        if (item.string == game_version) game_supported = true;
    }
    if (!game_supported) {
        set_error(error, "this mod does not support game version " + game_version);
        return false;
    }

    const Json* platforms = root.get("platforms");
    if (!platforms || platforms->kind != Json::Kind::Array || platforms->array.empty()) {
        set_error(error, "platforms must be a non-empty array");
        return false;
    }
    bool platform_supported = false;
    for (const Json& item : platforms->array) {
        if (item.kind != Json::Kind::String || item.string.empty()) {
            set_error(error, "platforms contains a non-string value");
            return false;
        }
        if (item.string == platform) platform_supported = true;
    }
    if (!platform_supported) {
        set_error(error, "this mod does not support platform " + platform);
        return false;
    }

    if (!string_field(root, "entrypoint", manifest.entrypoint, false, error)) return false;
    if (!manifest.entrypoint.empty()) {
        if (!safe_relative_path(manifest.entrypoint, false)) {
            set_error(error, "entrypoint is not a safe relative path");
            return false;
        }
        const bool exists = std::any_of(archive.entries.begin(), archive.entries.end(),
            [&](const ZipEntry& e) { return !e.directory && e.name == manifest.entrypoint; });
        if (!exists) {
            set_error(error, "entrypoint file is missing from the package");
            return false;
        }
    }

    std::string assets_directory;
    if (!string_field(root, "assetsDirectory", assets_directory, false, error)) return false;
    if (!assets_directory.empty() && !safe_relative_path(assets_directory, false)) {
        set_error(error, "assetsDirectory is not a safe relative path");
        return false;
    }

    const Json* dependencies = root.get("dependencies");
    if (dependencies) {
        if (dependencies->kind != Json::Kind::Object) {
            set_error(error, "dependencies must be an object");
            return false;
        }
        if (dependencies->object.size() > 128) {
            set_error(error, "too many mod dependencies");
            return false;
        }
        for (const auto& dependency : dependencies->object) {
            if (!valid_id(dependency.first) || dependency.second.kind != Json::Kind::String ||
                dependency.second.string.empty() || dependency.second.string.size() > 64) {
                set_error(error, "invalid dependency identifier or version constraint");
                return false;
            }
        }
    }
    return true;
}

bool load_package(const std::string& path, const std::string& game_version,
                  const std::string& platform, Archive& archive,
                  Manifest& manifest, std::string* error) {
    if (fs::path(path).extension() != ".fredl") {
        set_error(error, "package filename must end with .fredl");
        return false;
    }
    if (!read_file(path, archive.bytes, error) || !parse_zip(archive, error)) return false;

    const ZipEntry* manifest_entry = nullptr;
    for (const ZipEntry& entry : archive.entries) {
        if (entry.name == "manifest.json" && !entry.directory) {
            if (manifest_entry) {
                set_error(error, "package contains more than one root manifest");
                return false;
            }
            manifest_entry = &entry;
        }
    }
    if (!manifest_entry || manifest_entry->expanded_size > kMaxManifestBytes) {
        set_error(error, "package must contain a root manifest.json no larger than 256 KiB");
        return false;
    }

    std::vector<uint8_t> json_bytes;
    if (!expand_entry(archive, *manifest_entry, json_bytes, error)) return false;
    manifest.raw_json.assign(json_bytes.begin(), json_bytes.end());

    Json root;
    JsonParser parser(manifest.raw_json);
    if (!parser.parse(root, error)) return false;
    return validate_manifest(root, archive, game_version, platform, manifest, error);
}

bool write_error(char* output, size_t capacity, const std::string& error) {
    if (output && capacity) {
        const size_t n = std::min(capacity - 1, error.size());
        std::memcpy(output, error.data(), n);
        output[n] = '\0';
    }
    return false;
}

std::string json_escape(const std::string& value) {
    std::string out = "\"";
    for (unsigned char c : value) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buffer[7];
                    std::snprintf(buffer, sizeof(buffer), "\\u%04x", c);
                    out += buffer;
                } else out.push_back(static_cast<char>(c));
        }
    }
    out += '"';
    return out;
}

bool install_package(const std::string& path, const std::string& mods_directory,
                     const std::string& game_version, const std::string& platform,
                     std::string* error) {
    Archive archive;
    Manifest manifest;
    if (!load_package(path, game_version, platform, archive, manifest, error)) return false;

    std::error_code ec;
    const fs::path root = fs::absolute(fs::path(mods_directory), ec);
    if (ec) { set_error(error, "invalid mods directory"); return false; }
    fs::create_directories(root, ec);
    if (ec || !fs::is_directory(root, ec)) {
        set_error(error, "could not create or access mods directory");
        return false;
    }

    const fs::path staging = root / (".fredl-staging-" + manifest.id);
    const fs::path target = root / manifest.id;
    const fs::path backup = root / (".fredl-backup-" + manifest.id);
    if (fs::exists(staging, ec)) fs::remove_all(staging, ec);
    ec.clear();
    if (fs::exists(backup, ec)) fs::remove_all(backup, ec);
    ec.clear();
    fs::create_directories(staging, ec);
    if (ec) { set_error(error, "could not create private staging directory"); return false; }

    for (const ZipEntry& entry : archive.entries) {
        const std::string relative = normalized_zip_name(entry.name, entry.directory);
        const fs::path destination = staging / fs::path(relative);
        if (entry.directory) {
            fs::create_directories(destination, ec);
            if (ec) break;
            continue;
        }
        fs::create_directories(destination.parent_path(), ec);
        if (ec) break;
        std::vector<uint8_t> contents;
        if (!expand_entry(archive, entry, contents, error)) {
            fs::remove_all(staging, ec);
            return false;
        }
        std::ofstream output(destination, std::ios::binary | std::ios::trunc);
        if (!output || (!contents.empty() &&
            !output.write(reinterpret_cast<const char*>(contents.data(),
                static_cast<std::streamsize>(contents.size()))))) {
            ec = std::make_error_code(std::errc::io_error);
            break;
        }
    }
    if (ec) {
        fs::remove_all(staging, ec);
        set_error(error, "failed while extracting package into staging directory");
        return false;
    }

    bool backed_up = false;
    ec.clear();
    if (fs::exists(target, ec)) {
        fs::rename(target, backup, ec);
        if (ec) {
            fs::remove_all(staging, ec);
            set_error(error, "could not preserve the previous installed mod");
            return false;
        }
        backed_up = true;
    }
    ec.clear();
    fs::rename(staging, target, ec);
    if (ec) {
        if (backed_up) {
            std::error_code restore_error;
            fs::rename(backup, target, restore_error);
        }
        std::error_code cleanup_error;
        fs::remove_all(staging, cleanup_error);
        set_error(error, "could not atomically activate the extracted mod files");
        return false;
    }
    if (backed_up) {
        std::error_code cleanup_error;
        fs::remove_all(backup, cleanup_error);
    }
    return true;
}

} // namespace

extern "C" FL_EXPORT int FreedomLoaderValidateFredlFile(
    const char* package_path, const char* game_version, const char* platform,
    char* error, size_t error_capacity) {
    if (error && error_capacity) error[0] = '\0';
    if (!package_path || !*package_path || !game_version || !*game_version ||
        !platform || !*platform) return write_error(error, error_capacity, "invalid API arguments"), 0;
    Archive archive;
    Manifest manifest;
    std::string message;
    if (!load_package(package_path, game_version, platform, archive, manifest, &message)) {
        write_error(error, error_capacity, message);
        return 0;
    }
    return 1;
}

extern "C" FL_EXPORT int FreedomLoaderInstallFredlFile(
    const char* package_path, const char* mods_directory, const char* game_version,
    const char* platform, char* error, size_t error_capacity) {
    if (error && error_capacity) error[0] = '\0';
    if (!package_path || !*package_path || !mods_directory || !*mods_directory ||
        !game_version || !*game_version || !platform || !*platform) {
        write_error(error, error_capacity, "invalid API arguments");
        return 0;
    }
    std::string message;
    if (!install_package(package_path, mods_directory, game_version, platform, &message)) {
        write_error(error, error_capacity, message);
        return 0;
    }
    return 1;
}

extern "C" FL_EXPORT int FreedomLoaderScanFredlDirectory(
    const char* mods_directory, const char* game_version, const char* platform,
    char* report_json, size_t report_capacity) {
    if (report_json && report_capacity) report_json[0] = '\0';
    if (!mods_directory || !*mods_directory || !game_version || !*game_version ||
        !platform || !*platform || !report_json || report_capacity < 3) return -1;

    std::error_code ec;
    const fs::path directory(mods_directory);
    if (!fs::exists(directory, ec) || !fs::is_directory(directory, ec)) {
        std::strncpy(report_json, "[]", report_capacity);
        return 0;
    }

    std::vector<std::string> records;
    int count = 0;
    for (fs::directory_iterator it(directory, ec), end; !ec && it != end; it.increment(ec)) {
        std::error_code item_error;
        if (!it->is_regular_file(item_error) || item_error ||
            it->path().extension() != ".fredl") continue;
        ++count;
        Archive archive;
        Manifest manifest;
        std::string error;
        const bool valid = load_package(it->path().string(), game_version, platform, archive, manifest, &error);
        const std::string file = it->path().filename().string();
        records.push_back(std::string("{\"file\":") + json_escape(file) +
            ",\"valid\":" + (valid ? "true" : "false") +
            ",\"id\":" + (valid ? json_escape(manifest.id) : "null") +
            ",\"error\":" + json_escape(valid ? "" : error) + "}");
    }
    std::string report = "[";
    for (size_t i = 0; i < records.size(); ++i) {
        if (i) report += ',';
        report += records[i];
    }
    report += ']';
    if (report.size() + 1 > report_capacity) return -2;
    std::memcpy(report_json, report.c_str(), report.size() + 1);
    return count;
}
