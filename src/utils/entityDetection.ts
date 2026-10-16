export interface EntityFilterOption {
  id: string;
  label: string;
  keywords: string[];
}

export const KNOWN_ENTITIES: EntityFilterOption[] = [
  { id: 'verdant', label: 'Verdant Engine', keywords: ['verdant'] },
  { id: 'fractus', label: 'Fractus / Prime', keywords: ['fractus'] },
  { id: 'weaver', label: 'AI Weaver', keywords: ['weaver'] },
  { id: 'grain', label: 'Grain', keywords: ['grain'] },
  { id: 'lignum', label: 'Lignum', keywords: ['lignum'] },
  { id: 'gigas', label: 'Gigas', keywords: ['gigas'] },
  { id: 'splinter', label: 'Splinter', keywords: ['splinter'] },
  { id: 'hollow', label: 'Hollow', keywords: ['hollow'] },
  { id: 'dorceless', label: 'Dorceless', keywords: ['dorceless'] },
  { id: 'transgression', label: 'Transgression / Threat', keywords: ['transgression', 'threat', 'anticheat', 'rot'] },
];

export function detectEntityForFile(filename: string): string {
  const lower = filename.toLowerCase();
  for (const ent of KNOWN_ENTITIES) {
    if (ent.keywords.some((kw) => lower.includes(kw))) {
      return ent.id;
    }
  }
  return 'system';
}

export function getEntityLabel(entityId: string): string {
  const found = KNOWN_ENTITIES.find((e) => e.id === entityId);
  if (found) return found.label;
  if (entityId === 'system') return 'System / Core';
  return entityId;
}
