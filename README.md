 SimpleStatsAPI

A lightweight Bukkit/Paper plugin that collects player statistics and exposes them via a simple HTTP REST API.**

[![Build Status](https://img.shields.io/github/actions/workflow/status/edgeoforder/SimpleStatsAPI/build.yml?branch=main)](https://github.com/edgeoforder/SimpleStatsAPI/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Paper 1.21.1](https://img.shields.io/badge/Paper-1.21.1-brightgreen)](https://papermc.io/)


📦 Features

- Tracks **player kills**, **Ender Dragon kills**, **current level**, and **playtime** (in seconds).
- Stores data in a local **SQLite** database (`plugins/SimpleStatsAPI/stats.db`).
- Provides a built-in **HTTP server** with JSON responses.
- Supports **CORS** for easy integration with web dashboards.
- Tracks **global max online** record.

🚀 Installation

1. Download the latest `SimpleStatsAPI.jar` from the [Releases](https://github.com/404Nick/SimpleStatsAPI/releases/tag/server-monitoring) page.
2. Place the JAR file into your server's `plugins/` folder.
3. Start or restart your Paper server (version 1.21.1 or compatible).
4. The plugin will generate a default `config.yml` and create the database file on first run.

 ⚙️ Configuration

Edit `plugins/SimpleStatsAPI/config.yml`:

```yaml
# HTTP server settings
http-port: 8080
http-host: 0.0.0.0

# Allowed origins for CORS (use "*" to allow all)
cors-allowed-origins:
  - "*"
