// src/components/EvidencePanel.jsx
// Rich evidence panel shown inside the review table's AI Answer column.
// Replaces the plain "Security Policy.pdf" text with structured evidence.

import { useState } from 'react';
import { Tag, Tooltip, Typography, Collapse } from 'antd';
import {
    CheckCircleOutlined,
    FileTextOutlined,
    SafetyCertificateOutlined,
    StarOutlined,
} from '@ant-design/icons';

const { Text } = Typography;

// ─── Confidence band config ───────────────────────────────────────────────────

const CONFIDENCE_BAND = (score) => {
    if (score == null) return null;
    if (score >= 0.85) return 'HIGH';
    if (score >= 0.60) return 'MEDIUM';
    return 'LOW';
};

const BAND_CFG = {
    HIGH: {
        icon:       '🟢',
        label:      'High confidence',
        color:      '#52c41a',
        bg:         '#f6ffed',
        border:     '#b7eb8f',
        textColor:  '#135200',
    },
    MEDIUM: {
        icon:       '🟡',
        label:      'Medium confidence',
        color:      '#faad14',
        bg:         '#fffbe6',
        border:     '#ffe58f',
        textColor:  '#614700',
    },
    LOW: {
        icon:       '🔴',
        label:      'Low confidence',
        color:      '#ff4d4f',
        bg:         '#fff2f0',
        border:     '#ffccc7',
        textColor:  '#820014',
    },
};

// ─── Source chip ──────────────────────────────────────────────────────────────

function SourceChip({ label, icon }) {
    return (
        <span style={{
            display:        'inline-flex',
            alignItems:     'center',
            gap:            4,
            padding:        '2px 8px',
            borderRadius:   10,
            background:     '#e6f4ff',
            border:         '1px solid #91caff',
            fontSize:       11,
            color:          '#0958d9',
            fontWeight:     500,
            whiteSpace:     'nowrap',
        }}>
      {icon}
            {label}
    </span>
    );
}

// ─── Confidence block ─────────────────────────────────────────────────────────

function ConfidenceBlock({ score, evidence, fromLibrary }) {
    const band = CONFIDENCE_BAND(score);
    if (!band) return null;
    const cfg  = BAND_CFG[band];
    const pct  = Math.round((score ?? 0) * 100);

    // Parse evidence into source names (comma or semi-colon separated)
    const sources = evidence
        ? evidence
            .split(/[,;]/)
            .map(s => s.trim())
            .filter(Boolean)
        : [];

    return (
        <div style={{
            marginTop:    8,
            padding:      '8px 10px',
            borderRadius: 6,
            background:   cfg.bg,
            border:       `1px solid ${cfg.border}`,
            borderLeft:   `3px solid ${cfg.color}`,
        }}>

            {/* Confidence header */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: sources.length > 0 ? 6 : 0 }}>
                <span style={{ fontSize: 13 }}>{cfg.icon}</span>
                <Text strong style={{ fontSize: 12, color: cfg.textColor }}>{cfg.label}</Text>
                <span style={{
                    background:  cfg.color,
                    color:       '#fff',
                    borderRadius: 8,
                    padding:     '0 7px',
                    fontSize:    11,
                    fontWeight:  700,
                }}>
          {pct}%
        </span>
                {fromLibrary && (
                    <Tag
                        color="green"
                        icon={<StarOutlined />}
                        style={{ fontSize: 10, margin: 0 }}
                    >
                        Approved Answer
                    </Tag>
                )}
            </div>

            {/* Source chips */}
            {sources.length > 0 && (
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 4 }}>
                    <Text type="secondary" style={{ fontSize: 11, alignSelf: 'center' }}>Matched:</Text>
                    {sources.map((src, i) => (
                        <SourceChip
                            key={i}
                            label={src}
                            icon={<FileTextOutlined style={{ fontSize: 10 }} />}
                        />
                    ))}
                </div>
            )}

            {/* Low-confidence suggestion */}
            {band === 'LOW' && (
                <div style={{ marginTop: 4 }}>
                    <Text style={{ fontSize: 11, color: '#820014' }}>
                        <strong>No uploaded document covers this topic.</strong>
                        {' '}Upload the relevant policy to improve this answer.
                    </Text>
                </div>
            )}
        </div>
    );
}

// ─── Evidence quote block ─────────────────────────────────────────────────────
// Shows a snippet from the source document if `evidenceQuote` is provided.
// The backend can optionally populate this from the retrieved chunk text.

function EvidenceQuote({ section, quote }) {
    if (!quote) return null;
    return (
        <div style={{
            marginTop:    6,
            padding:      '6px 10px',
            borderRadius: 4,
            background:   '#fafafa',
            borderLeft:   '3px solid #d9d9d9',
        }}>
            {section && (
                <Text type="secondary" style={{ fontSize: 10, display: 'block', marginBottom: 2 }}>
                    {section}
                </Text>
            )}
            <Text
                italic
                style={{ fontSize: 12, color: '#595959', lineHeight: 1.5 }}
            >
                "{quote}"
            </Text>
        </div>
    );
}

// ─── Main component ───────────────────────────────────────────────────────────

/**
 * EvidencePanel
 *
 * Props:
 *   answer          string   – The answer text (ai_answer or manual_answer)
 *   evidence        string   – Source label(s), e.g. "Security Policy v4, SOC2 Report"
 *   evidenceSection string   – Optional section reference, e.g. "Section 5.2 Encryption"
 *   evidenceQuote   string   – Optional short verbatim excerpt from the source
 *   retrievalScore  number   – 0.0–1.0 cosine similarity
 *   status          string   – QuestionStatus enum value
 *   fromLibrary     boolean  – True if the answer was reused from the approved library
 *   compact         boolean  – Compact mode for table cells (default false)
 */
export default function EvidencePanel({
                                          answer,
                                          evidence,
                                          evidenceSection,
                                          evidenceQuote,
                                          retrievalScore,
                                          status,
                                          fromLibrary = false,
                                          compact = false,
                                      }) {
    const isEdited   = status === 'EDITED';
    const isRejected = status === 'REJECTED';

    if (!answer) {
        return (
            <Text type="secondary" style={{ fontSize: 12, fontStyle: 'italic' }}>
                Not yet generated
            </Text>
        );
    }

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>

            {/* Answer text */}
            <Text style={{ fontSize: 13, lineHeight: 1.6 }}>
                {answer}
            </Text>

            {/* Edited / reused badges */}
            {isEdited && (
                <Tag color="cyan" style={{ width: 'fit-content', fontSize: 10, marginTop: 2 }}>
                    Edited by reviewer
                </Tag>
            )}
            {fromLibrary && !isEdited && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 }}>
                    <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 12 }} />
                    <Text style={{ fontSize: 11, color: '#389e0d' }}>
                        Reused from previously approved answer
                    </Text>
                </div>
            )}

            {/* Evidence section label */}
            {evidence && evidence !== 'N/A' && (
                <div style={{ marginTop: 4 }}>
                    <span style={{ fontSize: 11, color: '#8c8c8c' }}>📄 </span>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                        {evidence}
                        {evidenceSection && (
                            <span style={{ marginLeft: 4, fontStyle: 'italic' }}>
                — {evidenceSection}
              </span>
                        )}
                    </Text>
                </div>
            )}

            {/* Evidence verbatim quote */}
            {!compact && evidenceQuote && (
                <EvidenceQuote section={evidenceSection} quote={evidenceQuote} />
            )}

            {/* Confidence + matched sources */}
            {!compact && (
                <ConfidenceBlock
                    score={retrievalScore}
                    evidence={evidence}
                    fromLibrary={fromLibrary}
                />
            )}

            {/* Compact mode: inline confidence badge only */}
            {compact && retrievalScore != null && (
                <div style={{ marginTop: 4 }}>
                    {(() => {
                        const band = CONFIDENCE_BAND(retrievalScore);
                        const cfg  = band ? BAND_CFG[band] : null;
                        if (!cfg) return null;
                        return (
                            <span style={{ fontSize: 11, color: cfg.textColor }}>
                {cfg.icon} {Math.round(retrievalScore * 100)}% confidence
              </span>
                        );
                    })()}
                </div>
            )}
        </div>
    );
}