// src/components/CoverageGauge.jsx
// A single reusable arc gauge + tier badge used on the coverage page
// and as a compact badge on the detail page.
import { Progress, Tag } from 'antd';

const TIER_CFG = {
    EXCELLENT: { color: '#52c41a', label: 'Excellent', bg: '#f6ffed', border: '#b7eb8f' },
    GOOD:      { color: '#73d13d', label: 'Good',      bg: '#f6ffed', border: '#b7eb8f' },
    FAIR:      { color: '#faad14', label: 'Fair',      bg: '#fffbe6', border: '#ffe58f' },
    POOR:      { color: '#ff7a45', label: 'Poor',      bg: '#fff2e8', border: '#ffbb96' },
    CRITICAL:  { color: '#ff4d4f', label: 'Critical',  bg: '#fff2f0', border: '#ffccc7' },
};

export function CoverageTierTag({ tier, style = {} }) {
    const cfg = TIER_CFG[tier] || TIER_CFG.FAIR;
    return (
        <Tag
            style={{
                background: cfg.bg,
                border: `1px solid ${cfg.border}`,
                color: cfg.color,
                fontWeight: 700,
                fontSize: 12,
                ...style,
            }}
        >
            {cfg.label}
        </Tag>
    );
}

export function CoverageBar({ percent, tier, size = 'default', showLabel = true }) {
    const cfg = TIER_CFG[tier] || TIER_CFG.FAIR;
    return (
        <Progress
            percent={percent}
            strokeColor={cfg.color}
            trailColor="#f0f0f0"
            format={showLabel ? (p) => (
                <span style={{ color: cfg.color, fontWeight: 700, fontSize: size === 'small' ? 12 : 16 }}>
          {p}%
        </span>
            ) : false}
            size={size === 'small' ? 'small' : 'default'}
        />
    );
}