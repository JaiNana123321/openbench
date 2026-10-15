{
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  outputs = { self, nixpkgs }: let
    systems = [ "aarch64-darwin" "x86_64-darwin" "x86_64-linux" "aarch64-linux" ];
    forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
  in {
    devShells = forAllSystems (pkgs: {
      default = pkgs.mkShell {
        packages = [
          # Back end: Spring Boot 3 on Java 17, built with Maven.
          pkgs.jdk17
          pkgs.maven

          # Client in web/: React + Vite + TypeScript, run with npm during development.
          # The Maven build installs its own pinned Node through frontend-maven-plugin; keep the major versions equal.
          pkgs.nodejs_24

          # psql matching the PostgreSQL 16 server. The server itself runs in Docker
          # (Testcontainers in tests, a container in deployment), so Docker Desktop must be running.
          pkgs.postgresql_16
        ];
      };
    });
  };
}
