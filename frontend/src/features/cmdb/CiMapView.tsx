import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import type { CiMap, MapNode } from './api';
import classes from './cmdb.module.css';

const W = 176;
const H = 52;
const RING = 210;

interface Placed {
  node: MapNode;
  x: number;
  y: number;
}

/**
 * Карта связей (CFG-03): корень в центре, соседи кольцами по расстоянию. Линия латуни — связь передаёт отказ,
 * стрелка — направление связи. На узле — число открытых инцидентов и изменений. Узел открывается с клавиатуры.
 */
export function CiMapView({ map, onOpen }: { map: CiMap; onOpen: (id: string) => void }) {
  const { t } = useTranslation();
  const placed = useMemo(() => layout(map), [map]);
  const byId = new Map(placed.map((p) => [p.node.ci.id, p]));
  const xs = placed.map((p) => p.x);
  const ys = placed.map((p) => p.y);
  const minX = Math.min(...xs) - W / 2 - 24;
  const minY = Math.min(...ys) - H / 2 - 24;
  const width = Math.max(...xs) + W / 2 + 24 - minX;
  const height = Math.max(...ys) + H / 2 + 24 - minY;

  return (
    <div className={classes.map}>
      <svg
        role="img"
        aria-label={t('cmdb.map.label')}
        viewBox={`${minX} ${minY} ${width} ${height}`}
        width={Math.max(width, 320)}
        height={height}
        style={{ display: 'block', margin: '0 auto' }}
      >
        <defs>
          <marker id="cmdb-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" className={classes.mapArrow} />
          </marker>
        </defs>
        {map.edges.map((e) => {
          const a = byId.get(e.sourceId);
          const b = byId.get(e.targetId);
          if (!a || !b) return null;
          const [x1, y1] = border(a, b);
          const [x2, y2] = border(b, a);
          return (
            <line
              key={e.id}
              x1={x1}
              y1={y1}
              x2={x2}
              y2={y2}
              className={`${classes.mapEdge} ${e.impactDirection !== 'none' ? classes.mapEdgeImpact : ''}`}
              markerEnd="url(#cmdb-arrow)"
            >
              <title>{e.name}</title>
            </line>
          );
        })}
        {placed.map(({ node, x, y }) => {
          const root = node.ci.id === map.rootId;
          const alerts = [
            node.openIncidents > 0 ? t('cmdb.map.incidents', { count: node.openIncidents }) : null,
            node.openChanges > 0 ? t('cmdb.map.changes', { count: node.openChanges }) : null,
          ].filter(Boolean);
          return (
            <g
              key={node.ci.id}
              transform={`translate(${x - W / 2} ${y - H / 2})`}
              className={`${classes.mapNode} ${root ? classes.mapRoot : ''} ${node.ci.status === 'retired' ? classes.mapRetired : ''}`}
              tabIndex={0}
              role="link"
              aria-label={`${node.ci.name}, ${node.ci.className}`}
              onClick={() => onOpen(node.ci.id)}
              onKeyDown={(k) => (k.key === 'Enter' ? onOpen(node.ci.id) : undefined)}
            >
              <rect width={W} height={H} rx={8} />
              <text x={10} y={20} className={classes.mapName}>
                {clip(node.ci.name, 22)}
              </text>
              <text x={10} y={38} className={alerts.length ? classes.mapAlert : classes.mapMeta}>
                {alerts.length ? alerts.join(' · ') : clip(node.ci.className, 26)}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}

function layout(map: CiMap): Placed[] {
  const rings = new Map<number, MapNode[]>();
  for (const n of map.nodes) rings.set(n.depth, [...(rings.get(n.depth) ?? []), n]);
  const out: Placed[] = [];
  for (const [depth, nodes] of rings) {
    if (depth === 0) {
      nodes.forEach((n) => out.push({ node: n, x: 0, y: 0 }));
      continue;
    }
    // Радиус растёт, если узлов на кольце больше, чем помещается без наложения.
    const radius = Math.max(RING * depth, (nodes.length * (W + 16)) / (2 * Math.PI));
    nodes.forEach((n, i) => {
      const angle = (2 * Math.PI * i) / nodes.length - Math.PI / 2 + (depth % 2 ? 0 : Math.PI / nodes.length);
      out.push({ node: n, x: Math.round(radius * Math.cos(angle)), y: Math.round(radius * Math.sin(angle) * 0.62) });
    });
  }
  return out;
}

/** Точка на границе прямоугольника узла a по направлению к b: стрелка не прячется под узлом. */
function border(a: Placed, b: Placed): [number, number] {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  if (dx === 0 && dy === 0) return [a.x, a.y];
  const sx = dx === 0 ? Infinity : W / 2 / Math.abs(dx);
  const sy = dy === 0 ? Infinity : H / 2 / Math.abs(dy);
  const s = Math.min(sx, sy);
  return [a.x + dx * s, a.y + dy * s];
}

function clip(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}
