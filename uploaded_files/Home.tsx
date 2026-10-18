/**
 * AI ASSISTANT MAINTENANCE NOTICE:
 * Whenever you update any wiki content or implement commit changes:
 * 1. Update the "Recent Highlights" list in the Wiki Maintenance Note below AND in `/wiki_assets/wiki/Home.txt`.
 * 2. Keep the underline strictly on: "The wiki is always updated before a new version releases."
 * 3. Wrap newly added/changed components across the wiki in `<UpdatedFrame id="..." isUpdated={true}>`.
 */
import React from 'react';
import { 
  ShieldAlert, 
  Compass, 
  Terminal, 
  BookOpen, 
  Skull, 
  Layers, 
  ArrowRight,
  Info,
  Clock,
  Sparkles
} from 'lucide-react';
import UpdatedFrame from '../UpdatedFrame';
import TitleDialPicker from '../TitleDialPicker';

export default function HomeView() {
  return (
    <div className="space-y-8 max-w-[1000px] mx-auto text-[#c9d1c9]">
      
      {/* 1. LOGO HERO BANNER WITH SECRET INTERACTIVE DIAL PICKER */}
      <UpdatedFrame id="home_hero_dial_picker" isUpdated={true}>
        <div className="relative overflow-hidden rounded-xl bg-[#0c0f0d] border border-[#1e2720] p-8 text-center shadow-lg space-y-2">
          <TitleDialPicker />
          <p className="text-xs sm:text-sm font-mono tracking-[0.2em] text-[#709978] uppercase">
            Official Survival & Horror Field Guide
          </p>
        </div>
      </UpdatedFrame>

      {/* 2. WIKI MAINTENANCE NOTE (UPDATED FRAME) */}
      <UpdatedFrame id="home_maintenance_notice" isUpdated={true}>
        <div className="p-5 bg-gradient-to-r from-[#17140b] via-[#0f110d] to-[#0c0e0c] border border-amber-500/40 rounded-xl space-y-3 shadow-lg">
          <div className="flex items-center gap-2 text-amber-400 font-mono text-xs uppercase font-bold tracking-wider">
            <Info className="w-4 h-4 text-amber-400 animate-pulse" />
            <span>Wiki Maintenance Note</span>
          </div>
          
          <div className="p-4 sm:p-5 bg-amber-950/30 border border-amber-500/30 rounded-lg text-amber-200 text-base sm:text-lg md:text-xl font-semibold leading-relaxed text-center flex items-center justify-center">
            <u>The wiki is always updated before a new version releases.</u>
          </div>

          <div className="text-xs text-[#9eb0a1] space-y-1.5 font-mono pt-1">
            <div className="text-[#a9d1b0] font-semibold uppercase text-[11px] tracking-wider">Recent Version Highlights:</div>
            <ul className="list-disc list-inside space-y-1.5 text-[#829285] pl-1">
              <li><strong className="text-amber-300">The Ash-Weaver 1.8 Dossier & State Matrix:</strong> Documented complete biological, behavioral, and symbiotic mechanics for The Ash-Weaver (Low threat, 40 HP, natural cellulose armor, immune to cactus and drown damage in CUSTOM:TheThicket). Added interactive 4-phase State Matrix (Substrate Gardening, Symbiotic Player Bonding with cross-dimensional follow teleportation within 32 blocks, Predictive Combat Evasion with 4 melee damage retaliation, and Trauma Memory stress recovery) and 7 accordion behavioral logic modules.</li>
              <li><strong className="text-amber-300">New Backwoods Architectural Features:</strong> Added technical dossiers and bio-structural lore for Prototaxites (7-8m pre-vascular fungal-lichen fossil monoliths in Wood Plains), Timber Beams (high-altitude petrified ligaments spanning 14-70m canyon chasms), and Flying Buttress Shoring (diagonal valley-to-mountain compressive tendon struts).</li>
              <li><strong className="text-amber-300">Oak Pyramid & Menger Sponge Calibrations:</strong> Documented the Oak Pyramid 1 in 1,000 chunks (0.1% per chunk) rarity filter, and updated the Small Menger Sponge composition to 98% Petrified Oak Planks, 1% Splintered Oak, and 1% Lignum Caro with internal Furnace, Bed, and Chest loot chambers.</li>
              <li><strong className="text-amber-300">Void Basement Categorization:</strong> Reclassified the Void Basement structure under The Grain dimension, detailing active generation across all 5 Grain biomes (Uniform Grain, Stillwood, Pillar Thicket, Fractured Barrens, and Labyrinthine Grids).</li>
              <li><strong className="text-amber-300">Mental Degradation & Realm Defenses:</strong> Documented Stage 4 Prosopagnosia visual-neural breakdowns, anti-cheese realm defenses (anti-TNT, anti-lava, and domino fire extinguishment), and 24-block entity proximity sensor obfuscation.</li>
            </ul>
          </div>
        </div>
      </UpdatedFrame>

      {/* 3. WELCOME & OVERVIEW */}
      <div className="p-6 bg-[#0f1210] border border-[#1d251e] rounded-xl space-y-4 shadow-md">
        <p className="text-sm sm:text-base leading-relaxed text-[#d1dad1]">
          <strong className="text-[#e0e7e0]">Backwoods</strong> is a Minecraft horror mod that introduces terrifying dimensions, hostile entities with complex behavior profiles, custom combat mechanics, atmospheric blocks, and a psychological <strong className="text-amber-400">Mental Degradation</strong> survival system.
        </p>
        <p className="text-xs sm:text-sm leading-relaxed text-[#829285] italic border-l-2 border-[#709978] pl-4">
          The mod aims to diversify and intensify survival through oppressive exploration, limited safety, and enemies that require awareness and timing rather than simple combat. With Backwoods, you will once again feel fear while traveling, looting, and descending into dark places — because danger is no longer just around you; it is watching you.
        </p>
      </div>

      {/* 4. NAVIGATION CARDS GRID */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
        
        {/* CARD 1: GETTING STARTED */}
        <div className="p-5 bg-[#0c0e0c] border border-[#1a221c] border-t-2 border-t-[#709978] rounded-xl space-y-3 flex flex-col justify-between hover:border-[#2a382d] transition-all">
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-[#e0e7e0] font-serif text-base font-semibold">
              <Compass className="w-4 h-4 text-[#709978]" />
              <span>Getting Started</span>
            </div>
            <p className="text-xs text-[#829285] leading-relaxed">
              Build a portal frame out of <strong className="text-[#c9d1c9]">Oak Planks</strong> and ignite it using <strong className="text-[#c9d1c9]">Steel and Charcoal</strong>.
            </p>
            <p className="text-xs text-[#5a6b5e] italic">
              Step into a vast, yellow-fogged dimension constructed of decaying planks and logs.
            </p>
          </div>
          <a href="#/wiki/dimensions" className="inline-flex items-center gap-1.5 text-xs font-mono text-[#709978] hover:text-[#a9d1b0] uppercase tracking-wider font-semibold pt-2">
            <span>Explore Realms</span>
            <ArrowRight className="w-3.5 h-3.5" />
          </a>
        </div>

        {/* CARD 2: MENTAL DEGRADATION */}
        <div className="p-5 bg-[#0c0e0c] border border-[#1a221c] border-t-2 border-t-[#709978] rounded-xl space-y-3 flex flex-col justify-between hover:border-[#2a382d] transition-all">
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-[#e0e7e0] font-serif text-base font-semibold">
              <Skull className="w-4 h-4 text-[#709978]" />
              <span>Mental Degradation</span>
            </div>
            <p className="text-xs text-[#829285] leading-relaxed">
              The longer you linger in complete darkness, the more your sanity deteriorates. Learn how fear mechanics warp audio, vision, and survival.
            </p>
          </div>
          <a href="#/wiki/sanity" className="inline-flex items-center gap-1.5 text-xs font-mono text-[#709978] hover:text-[#a9d1b0] uppercase tracking-wider font-semibold pt-2">
            <span>Sanity Guide</span>
            <ArrowRight className="w-3.5 h-3.5" />
          </a>
        </div>

        {/* CARD 3: HOSTILE ENTITIES */}
        <div className="p-5 bg-[#0c0e0c] border border-[#1a221c] border-t-2 border-t-[#709978] rounded-xl space-y-3 flex flex-col justify-between hover:border-[#2a382d] transition-all">
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-[#e0e7e0] font-serif text-base font-semibold">
              <ShieldAlert className="w-4 h-4 text-[#709978]" />
              <span>Hostile Entities</span>
            </div>
            <p className="text-xs text-[#829285] leading-relaxed">
              They are watching. Inspect full 3D models, armor ratings, drop rates, and combat counters for every creature in the Backwoods.
            </p>
          </div>
          <a href="#/wiki/entities" className="inline-flex items-center gap-1.5 text-xs font-mono text-[#709978] hover:text-[#a9d1b0] uppercase tracking-wider font-semibold pt-2">
            <span>View Bestiary</span>
            <ArrowRight className="w-3.5 h-3.5" />
          </a>
        </div>

      </div>

      {/* 5. QUICK DIRECTORY LINKS */}
      <div className="p-6 bg-[#090b09] border border-[#161d17] rounded-xl space-y-4">
        <div className="text-xs font-mono text-[#5a6b5e] uppercase tracking-widest font-semibold">
          Wiki Quick Reference
        </div>
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-6 gap-3 text-center">
          {[
            { label: 'Entities', href: '#/wiki/entities', icon: ShieldAlert },
            { label: 'Commands', href: '#/wiki/commands', icon: Terminal },
            { label: 'Dimensions', href: '#/wiki/dimensions', icon: Compass },
            { label: 'Blocks', href: '#/wiki/blocks', icon: Layers },
            { label: 'Items', href: '#/wiki/items', icon: BookOpen },
            { label: 'Versions', href: '#/wiki/versions', icon: Clock }
          ].map(item => (
            <a
              key={item.label}
              href={item.href}
              className="p-3 bg-[#0d100e] border border-[#1a221c] hover:border-[#709978]/50 hover:bg-[#121714] rounded-lg transition-all flex flex-col items-center gap-2 group"
            >
              <item.icon className="w-4 h-4 text-[#709978] group-hover:scale-110 transition-transform" />
              <span className="text-xs font-mono text-[#c9d1c9] group-hover:text-[#a9d1b0]">{item.label}</span>
            </a>
          ))}
        </div>
      </div>

      {/* FOOTER */}
      <div className="text-center pt-6 border-t border-[#1a221c] text-[11px] font-mono text-[#5a6b5e] uppercase tracking-widest">
        Created by honeypie_3301 using MCreator
      </div>

    </div>
  );
}
