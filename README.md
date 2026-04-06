# <img src="https://i.imgur.com/WrDX16M.png" width="48" height="48" valign="middle"> Nascraft

[![Build Status](https://github.com/Bounser/Nascraft/actions/workflows/gradle.yml/badge.svg)](https://github.com/Bounser/Nascraft/actions)
[![SpigotMC](https://img.shields.io/badge/SpigotMC-108216-orange.svg)](https://www.spigotmc.org/resources/108216/)

**Nascraft** is a high-performance, dynamic stock-market style economy plugin for Minecraft. It allows items to have live valuations based on supply and demand, featuring real-time in-game graphs, a comprehensive web interface, and deep Discord integration.

## ✨ Key Features

*   📈 **Live Valuations:** Item prices fluctuate dynamically based on player trading activity (supply and demand).
*   📊 **Real-time Graphs:** In-game visual price history powered by **AdvancedGUI**.
*   🌐 **Web Dashboard:** A modern, interactive web interface for players to track the market and manage their portfolios remotely.
*   💳 **Portfolio System:** Investors can hold "stocks" of items in a dedicated portfolio to profit from market movements.
*   🤖 **Discord Bridge:** Full Discord integration including price alerts, market logs, and account linking.
*   ⚡ **High Performance:** Built with concurrency in mind (using `CopyOnWriteArrayList` and `ConcurrentHashMap`) and optimized sorting (O(n log n)).
*   🌍 **Multi-language Support:** Available in English, Spanish, German, Italian, Portuguese, Russian, and Chinese.

## 🛠️ Requirements

*   **Java 15+**
*   **Spigot/Paper 1.16.5 - 1.21.x**
*   **Vault** (for economy integration)
*   **AdvancedGUI** (Optional, for in-game graphs)
*   **PlaceholderAPI** (Optional, for custom placeholders)

## 🚀 Getting Started

### Installation
1.  Download the latest release from [SpigotMC](https://www.spigotmc.org/resources/108216/).
2.  Place the `Nascraft.jar` in your server's `plugins/` folder.
3.  Restart your server.
4.  Configure items and categories in `plugins/Nascraft/items.yml` and `categories.yml`.

### Build from Source
We use **Gradle** for our build system.
```bash
git clone https://github.com/Bounser/Nascraft.git
cd Nascraft
./gradlew shadowJar
```
The resulting jar will be in `build/libs/`.

## 📜 Commands & Permissions

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/market` | Opens the main market GUI | `nascraft.market` |
| `/portfolio` | Manage your item portfolio | `nascraft.portfolio` |
| `/nascraft` | Admin command for market management | `nascraft.admin` |
| `/sellhand` | Quickly sell the item in your hand | `nascraft.sell` |
| `/alerts` | Manage your price alerts | `nascraft.alerts` |

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request. For major changes, please open an issue first to discuss what you would like to change.

## 📄 License

This project is licensed under the [MIT License](LICENSE).

---
*For a full detailed description, visit the [SpigotMC page](https://www.spigotmc.org/resources/108216/).*
