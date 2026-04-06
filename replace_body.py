import re

with open('src/main/resources/web/index.html', 'r') as f:
    content = f.read()

new_body = """<body class="bg-[#0f1115] text-gray-100 font-sans antialiased overflow-hidden flex flex-col h-screen selection:bg-indigo-500/30">

    <!-- Mobile Header -->
    <header class="sm:hidden flex items-center justify-between bg-gray-900/80 backdrop-blur-md p-4 border-b border-gray-800 z-40 relative">
        <div class="flex items-center gap-3">
            <img src="/api/icons/logo-color.png" alt="Logo" class="w-7 h-7" onerror="this.src='/api/icons/logo.png'">
            <span class="font-bold text-lg tracking-tight bg-clip-text text-transparent bg-gradient-to-r from-indigo-400 to-cyan-400">Nascraft</span>
        </div>
        <button id="mobile-menu-btn" class="p-2 text-gray-400 hover:text-white focus:outline-none rounded-lg hover:bg-gray-800 transition-colors">
            <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16"></path></svg>
        </button>
    </header>

    <div class="flex flex-1 overflow-hidden relative">

        <!-- Sidebar / Drawer -->
        <aside id="sidebar" class="absolute inset-y-0 left-0 z-50 w-72 bg-gray-900/95 backdrop-blur-xl border-r border-gray-800 transform -translate-x-full transition-transform duration-300 ease-in-out sm:relative sm:translate-x-0 sm:flex flex-col shrink-0 h-full shadow-2xl sm:shadow-none">
            
            <div class="hidden sm:flex items-center gap-3 p-5 border-b border-gray-800/60">
                <img src="/api/icons/logo-color.png" alt="Logo" class="w-8 h-8" onerror="this.src='/api/icons/logo.png'">
                <span class="font-bold text-xl tracking-tight bg-clip-text text-transparent bg-gradient-to-r from-indigo-400 to-cyan-400">Nascraft</span>
            </div>

            <div class="sm:hidden flex justify-between items-center p-4 border-b border-gray-800/60">
                <span class="font-semibold text-gray-200">Market Menu</span>
                <button id="close-sidebar-btn" class="p-2 text-gray-400 hover:text-white rounded-lg hover:bg-gray-800 transition-colors">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path></svg>
                </button>
            </div>

            <div class="p-4 flex-1 flex flex-col overflow-hidden">
                <div class="relative mb-4">
                     <span class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-gray-500">
                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="m21 21-5.197-5.197m0 0A7.5 7.5 0 1 0 5.196 5.196a7.5 7.5 0 0 0 10.607 10.607Z" /></svg>
                     </span>
                    <input type="text" id="search-input" placeholder="Search Items..." class="w-full pl-9 pr-3 py-2.5 bg-gray-800/50 border border-gray-700/50 rounded-xl focus:outline-none focus:ring-2 focus:ring-indigo-500/50 focus:border-indigo-500 text-sm transition-all shadow-inner text-gray-200 placeholder-gray-500">
                </div>

                <div class="flex items-center justify-between mb-2 text-xs text-gray-400 px-1">
                    <span class="font-medium uppercase tracking-wider text-gray-500 text-[10px]">Sort by</span>
                </div>
                
                <div id="sort-controls" class="flex gap-1.5 mb-3">
                     <button class="sort-button flex-1 py-1.5 px-2 bg-gray-800/40 hover:bg-gray-700 rounded-lg text-xs font-medium text-gray-400 border border-transparent transition-colors" data-sort-category="price">
                         <span id="label-sort-price">Price</span> <span class="sort-indicator ml-1"></span>
                     </button>
                     <button class="sort-button flex-1 py-1.5 px-2 bg-gray-800/40 hover:bg-gray-700 rounded-lg text-xs font-medium text-gray-400 border border-transparent transition-colors" data-sort-category="change">
                         <span id="label-sort-change">Change</span> <span class="sort-indicator ml-1"></span>
                     </button>
                     <button class="sort-button flex-1 py-1.5 px-2 bg-gray-800/40 hover:bg-gray-700 rounded-lg text-xs font-medium text-gray-400 border border-transparent transition-colors" data-sort-category="operations">
                         <span id="label-sort-popular">Popular</span> <span class="sort-indicator ml-1"></span>
                     </button>
                </div>

                <nav class="flex-1 overflow-y-auto custom-scrollbar -mr-2 pr-2">
                    <ul id="item-list" class="space-y-1">
                        <li class="p-4 text-sm text-gray-500 text-center" id="label-loading-items">Loading items...</li>
                    </ul>
                </nav>
            </div>

            <div class="p-4 border-t border-gray-800/60 text-xs text-gray-500 flex justify-between items-center bg-gray-900/30">
                <span>Powered by</span>
                <a href="https://www.spigotmc.org/resources/108216/" target="_blank" rel="noopener noreferrer" class="font-medium text-indigo-400 hover:text-indigo-300 transition-colors">Nascraft</a>
            </div>
        </aside>

        <!-- Overlay -->
        <div id="mobile-overlay" class="fixed inset-0 bg-black/60 backdrop-blur-sm z-40 hidden opacity-0 transition-opacity duration-300 sm:hidden"></div>

        <!-- Main Dashboard -->
        <main class="flex-1 overflow-y-auto custom-scrollbar relative p-4 sm:p-6 lg:p-8 w-full">
            <div class="max-w-7xl mx-auto flex flex-col gap-6 pb-10">
                
                <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
                    
                    <!-- Chart Section -->
                    <section id="chart-section" class="lg:col-span-2 bg-gray-800/40 backdrop-blur-md border border-gray-700/50 p-5 sm:p-6 rounded-2xl shadow-xl flex flex-col relative overflow-hidden">
                        <div class="absolute top-0 left-0 w-full h-1 bg-gradient-to-r from-indigo-500 to-cyan-400"></div>
                        
                        <div class="flex flex-col sm:flex-row sm:items-start sm:justify-between mb-4 gap-4">
                             <div class="flex items-center">
                                <div class="p-2 bg-gray-900/50 rounded-xl mr-4 border border-gray-700/50 shadow-inner">
                                    <img id="selected-item-icon" src="https://placehold.co/32x32/1f2937/9ca3af?text=?" alt="Item Icon" class="w-10 h-10 rounded-lg object-contain">
                                </div>
                                <div>
                                    <h2 id="selected-item-name" class="text-2xl font-bold text-white tracking-tight">Select an Item</h2>
                                    <p id="selected-item-description" class="text-sm text-gray-400 mt-1">Select an item to see its price evolution.</p>
                                </div>
                             </div>
                             
                             <div class="flex flex-row sm:flex-col items-center sm:items-end gap-3 sm:gap-2 flex-wrap">
                                 <div class="flex items-center gap-2 bg-gray-900/50 px-3 py-1.5 rounded-lg border border-gray-700/50">
                                      <input type="checkbox" id="inflation-adjust-checkbox" class="chart-option-checkbox w-4 h-4 rounded text-indigo-500 bg-gray-700 border-gray-600 focus:ring-indigo-500 focus:ring-offset-gray-900">
                                      <label for="inflation-adjust-checkbox" class="chart-option-label text-xs font-medium text-gray-300 m-0 cursor-pointer" id="label-adjust-inflation">Inflation Adj</label>
                                 </div>
                                 <div class="flex items-center gap-2 bg-gray-900/50 px-3 py-1.5 rounded-lg border border-gray-700/50">
                                       <input type="checkbox" id="log-scale-checkbox" class="chart-option-checkbox w-4 h-4 rounded text-indigo-500 bg-gray-700 border-gray-600 focus:ring-indigo-500 focus:ring-offset-gray-900">
                                       <label for="log-scale-checkbox" class="chart-option-label text-xs font-medium text-gray-300 m-0 cursor-pointer" id="label-log-scale">Log Scale</label>
                                 </div>
                                  <button id="reset-zoom-button" class="text-xs font-medium bg-gray-700 hover:bg-gray-600 text-white py-1.5 px-3 rounded-lg shadow transition-colors border border-gray-600 hover:border-gray-500">
                                      <span id="label-reset-zoom">Reset Zoom</span>
                                  </button>
                             </div>
                        </div>
                        
                        <div class="chart-container relative flex-grow bg-gray-900/30 rounded-xl border border-gray-700/30 mt-2 min-h-[300px] sm:min-h-[400px]">
                            <div id="item-price-chart-container"></div>
                            <div id="chart-loading-spinner" class="absolute inset-0 flex items-center justify-center bg-gray-900/40 backdrop-blur-[2px] z-20 rounded-xl hidden">
                                <div class="w-10 h-10 border-4 border-t-indigo-500 border-indigo-500/20 rounded-full animate-spin"></div>
                            </div>
                        </div>
                    </section>

                    <!-- Item Details Section -->
                    <section class="lg:col-span-1 bg-gray-800/40 backdrop-blur-md border border-gray-700/50 p-5 sm:p-6 rounded-2xl shadow-xl flex flex-col relative overflow-hidden">
                        <div class="absolute top-0 left-0 w-full h-1 bg-gradient-to-r from-emerald-400 to-emerald-600"></div>
                        
                        <h3 class="text-lg font-bold text-white mb-4 flex items-center gap-2" id="label-item-info">
                            <svg class="w-5 h-5 text-emerald-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>
                            Item Info
                        </h3>

                        <div id="item-details" class="flex flex-col gap-4 flex-grow">
                            <!-- Main Price Card -->
                            <div class="bg-gray-900/50 rounded-xl p-4 border border-gray-700/50 shadow-inner text-center">
                                <span class="text-xs font-semibold text-gray-400 uppercase tracking-widest block mb-1" id="label-current-price-title">Current Price</span>
                                <p class="text-3xl font-extrabold text-white tracking-tight" id="current-price">$0.00</p>
                                <div class="mt-2 flex justify-center items-center gap-3">
                                    <div class="flex flex-col items-center">
                                        <span class="text-[10px] text-gray-500 uppercase font-semibold" id="label-1h-change-title">1h Change</span>
                                        <span class="text-sm font-bold" id="change-1h">-</span>
                                    </div>
                                    <div class="w-px h-6 bg-gray-700"></div>
                                    <div class="flex flex-col items-center">
                                        <span class="text-[10px] text-gray-500 uppercase font-semibold" id="label-market-rank-title">Rank</span>
                                        <span class="text-sm font-bold text-indigo-400" id="market-rank">#?</span>
                                    </div>
                                </div>
                            </div>

                            <!-- Stats Grid -->
                            <div class="grid grid-cols-2 gap-3">
                                 <div class="bg-gray-900/30 p-3 rounded-xl border border-gray-700/30">
                                     <span class="text-[10px] text-gray-500 uppercase font-semibold block mb-1" id="label-inception-return-title">Total Return</span>
                                     <p class="text-sm font-bold" id="inception-return">-</p>
                                 </div>
                                 <div class="bg-gray-900/30 p-3 rounded-xl border border-gray-700/30">
                                     <span class="text-[10px] text-gray-500 uppercase font-semibold block mb-1" id="label-volatility-title">Volatility</span>
                                     <p class="text-sm font-bold text-blue-400" id="volatility">-</p>
                                 </div>
                                 <div class="bg-gray-900/30 p-3 rounded-xl border border-gray-700/30">
                                     <span class="text-[10px] text-gray-500 uppercase font-semibold block mb-1" id="label-all-time-high-title">ATH</span>
                                     <p class="text-sm font-bold text-emerald-400" id="all-time-high">$0.00</p>
                                 </div>
                                 <div class="bg-gray-900/30 p-3 rounded-xl border border-gray-700/30">
                                     <span class="text-[10px] text-gray-500 uppercase font-semibold block mb-1" id="label-all-time-low-title">ATL</span>
                                     <p class="text-sm font-bold text-red-400" id="all-time-low">$0.00</p>
                                 </div>
                            </div>
                            
                            <div class="bg-gray-900/30 p-3 rounded-xl border border-gray-700/30">
                                <span class="text-[10px] text-gray-500 uppercase font-semibold block mb-1" id="label-max-drawdown-title">Max Drawdown</span>
                                <p class="text-sm font-bold text-orange-400" id="max-drawdown">-</p>
                            </div>
                        </div>

                         <div class="grid grid-cols-2 gap-3 pt-5 mt-auto border-t border-gray-700/50">
                            <button id="sell-button" class="w-full relative overflow-hidden group bg-rose-500/10 hover:bg-rose-500/20 border border-rose-500/30 text-rose-400 hover:text-rose-300 font-bold py-2.5 px-4 rounded-xl text-sm transition-all duration-200 shadow-[0_0_15px_rgba(244,63,94,0.1)] hover:shadow-[0_0_20px_rgba(244,63,94,0.2)]">
                                <span class="relative z-10" id="label-sell-button">Sell <span id="sell-price-display" class="opacity-80 font-medium ml-1">-</span></span>
                            </button>
                             <button id="buy-button" class="w-full relative overflow-hidden group bg-emerald-500/10 hover:bg-emerald-500/20 border border-emerald-500/30 text-emerald-400 hover:text-emerald-300 font-bold py-2.5 px-4 rounded-xl text-sm transition-all duration-200 shadow-[0_0_15px_rgba(16,185,129,0.1)] hover:shadow-[0_0_20px_rgba(16,185,129,0.2)]">
                                 <span class="relative z-10" id="label-buy-button">Buy <span id="buy-price-display" class="opacity-80 font-medium ml-1">-</span></span>
                            </button>
                        </div>
                         <div id="additional-info" class="mt-auto pt-3 text-sm text-gray-400 hidden">
                         </div>
                    </section>
                </div>

                <!-- Dividers -->
                <div class="flex items-center gap-4 mt-4">
                     <div class="h-px flex-1 bg-gradient-to-r from-transparent via-gray-700 to-transparent"></div>
                     <h3 class="text-center text-sm font-bold tracking-widest uppercase text-gray-500" id="label-market-data">Market Data</h3>
                     <div class="h-px flex-1 bg-gradient-to-r from-transparent via-gray-700 to-transparent"></div>
                </div>

                <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
                    <section class="bg-gray-800/40 backdrop-blur-md border border-gray-700/50 p-5 rounded-2xl shadow-xl relative overflow-hidden flex flex-col">
                        <div class="absolute top-0 left-0 w-full h-1 bg-gradient-to-r from-orange-400 to-pink-500"></div>
                        <h3 class="text-base font-bold text-white mb-4 flex items-center gap-2" id="label-cpi-evolution">
                            <svg class="w-5 h-5 text-orange-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6"></path></svg>
                            CPI Evolution
                        </h3>
                        <div class="chart-container h-[280px] bg-gray-900/30 rounded-xl border border-gray-700/30 p-2 relative flex-grow">
                            <canvas id="cpi-chart"></canvas>
                        </div>
                    </section>

                    <section class="bg-gray-800/40 backdrop-blur-md border border-gray-700/50 p-5 rounded-2xl shadow-xl relative overflow-hidden flex flex-col">
                        <div class="absolute top-0 left-0 w-full h-1 bg-gradient-to-r from-blue-400 to-indigo-500"></div>
                        <h3 class="text-base font-bold text-white mb-4 flex items-center gap-2" id="label-market-cap">
                            <svg class="w-5 h-5 text-blue-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 3.055A9.001 9.001 0 1020.945 13H11V3.055z"></path><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.488 9H15V3.512A9.025 9.025 0 0120.488 9z"></path></svg>
                            Market Cap Distribution
                        </h3>
                        <div class="chart-container h-[280px] bg-gray-900/30 rounded-xl border border-gray-700/30 overflow-hidden relative flex-grow">
                            <div id="treemap-container"></div>
                        </div>
                    </section>
                </div>

                <div class="flex items-center gap-4 mt-4">
                     <div class="h-px flex-1 bg-gradient-to-r from-transparent via-gray-700 to-transparent"></div>
                     <h3 class="text-center text-sm font-bold tracking-widest uppercase text-gray-500" id="label-portfolio-info">Top Portfolios</h3>
                     <div class="h-px flex-1 bg-gradient-to-r from-transparent via-gray-700 to-transparent"></div>
                </div>

                <div id="top-portfolios-container" class="w-full grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-4">
                     <p class="text-gray-500 text-sm text-center py-8 col-span-full" id="label-loading-portfolios">Loading top portfolios...</p>
                </div>
            </div>
        </main>
    </div>
    
    <div id="treemap-tooltip" class="absolute hidden bg-gray-900/95 backdrop-blur-md border border-gray-700 text-gray-200 text-xs px-3 py-2 rounded-lg shadow-2xl pointer-events-none z-50"></div>

    <script src="script.js" defer></script>
</body>"""

new_content = re.sub(r'<body.*</body>', new_body, content, flags=re.DOTALL)

with open('src/main/resources/web/index.html', 'w') as f:
    f.write(new_content)
