#include "freedom_fredl.h"

#include <cstdio>
#include <cstring>

int main(int argc, char** argv) {
    if (argc >= 2 && std::strcmp(argv[1], "validate") == 0 && argc == 5) {
        char error[1024]{};
        const int okay = FreedomLoaderValidateFredlFile(argv[2], argv[3], argv[4], error, sizeof(error));
        if (okay) {
            std::puts("VALID");
            return 0;
        }
        std::printf("INVALID: %s\n", error);
        return 2;
    }
    if (argc >= 2 && std::strcmp(argv[1], "install") == 0 && argc == 6) {
        char error[1024]{};
        const int okay = FreedomLoaderInstallFredlFile(argv[2], argv[3], argv[4], argv[5], error, sizeof(error));
        if (okay) {
            std::puts("INSTALLED");
            return 0;
        }
        std::printf("INSTALL_FAILED: %s\n", error);
        return 3;
    }
    if (argc >= 2 && std::strcmp(argv[1], "scan") == 0 && argc == 5) {
        char report[128 * 1024]{};
        const int count = FreedomLoaderScanFredlDirectory(argv[2], argv[3], argv[4], report, sizeof(report));
        if (count < 0) {
            std::printf("SCAN_FAILED: %d\n", count);
            return 4;
        }
        std::puts(report);
        return 0;
    }
    if (argc >= 2 && std::strcmp(argv[1], "list") == 0 && argc == 5) {
        char report[256 * 1024]{};
        const int count = FreedomLoaderGetInstalledModsReport(argv[2], argv[3], argv[4], report, sizeof(report));
        if (count < 0) {
            std::printf("LIST_FAILED: %d\n", count);
            return 5;
        }
        std::puts(report);
        return 0;
    }
    if (argc >= 2 && std::strcmp(argv[1], "select") == 0 && argc == 7) {
        char error[1024]{};
        const bool selected = std::strcmp(argv[4], "on") == 0;
        if (!selected && std::strcmp(argv[4], "off") != 0) return 64;
        if (FreedomLoaderSetModSelected(argv[2], argv[3], selected ? 1 : 0,
                                        argv[5], argv[6], error, sizeof(error))) {
            std::puts(selected ? "SELECTED" : "DESELECTED");
            return 0;
        }
        std::printf("SELECTION_FAILED: %s\n", error);
        return 6;
    }
    std::fprintf(stderr,
        "Usage:\n"
        "  %s validate <package.fredl> <game-version> <platform>\n"
        "  %s install <package.fredl> <mods-dir> <game-version> <platform>\n"
        "  %s scan <mods-dir> <game-version> <platform>\n"
        "  %s list <mods-dir> <game-version> <platform>\n"
        "  %s select <mods-dir> <mod-id> <on|off> <game-version> <platform>\n",
        argv[0], argv[0], argv[0], argv[0], argv[0]);
    return 64;
}
