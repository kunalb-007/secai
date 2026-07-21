// REPLACE ENTIRE FILE: src/components/EvidencePanel.jsx

import { useState } from 'react';
import { Tag, Tooltip, Typography } from 'antd';
import {
    CheckCircleOutlined,
    FileTextOutlined,
    StarOutlined,
    LinkOutlined,
    InfoCircleOutlined,
} from '@ant-design/icons';
import EvidenceDetailsModal from './EvidenceDetailsModal';

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
        icon:      '🟢',
        label:     'High confidence',
        color:     '#52c41a',
        bg:        '#f6ffed',
        border:    '#b7eb8f',
        textColor: '#135200',
        barColor:  '#52c41a',
    },
    MEDIUM: {
        icon:      '🟡',
        label:     'Medium confidence — review recommended',
        color:     '#faad14',
        bg:        '#fffbe6',
        border:    '#ffe58f',
        textColor: '#614700',
        barColor:  '#faad14',
    },
    LOW: {
        icon:      '🔴',
        label:     'Low confidence — likely missing document',
        color:     '#ff4d4f',
        bg:        '#fff2f0',
        border:    '#ffccc7',
        textColor: '#820014',
        barColor:  '#ff4d4f',
    },
};

// ─── Clickable source chip — opens Evidence Details Modal ─────────────────────
// No longer navigates to /documents/:id directly.

function SourceChip({ label, clickable, onClick }) {
    return (
        <Tooltip title={clickable ? 'View evidence details' : label}>
            <span
                onClick={clickable ? (e) => { e.stopPropagation(); onClick(); } : undefined}
                role={clickable ? 'button' : undefined}
                tabIndex={clickable ? 0 : undefined}
                onKeyDown={
                    clickable
                        ? (e) => {
                            if (e.key === 'Enter') {
                                e.stopPropagation();
                                onClick();
                            }
                        }
                        : undefined
                }
                style={{
                    display:      'inline-flex',
                    alignItems:   'center',
                    gap:          4,
                    padding:      '3px 10px',
                    borderRadius: 12,
                    background:   clickable ? '#e6f4ff' : '#f5f5f5',
                    border:       clickable ? '1px solid #91caff' : '1px solid #d9d9d9',
                    fontSize:     11,
                    color:        clickable ? '#0958d9' : '#595959',
                    fontWeight:   500,
                    whiteSpace:   'nowrap',
                    cursor:       clickable ? 'pointer' : 'default',
                    transition:   'background 0.15s, border-color 0.15s',
                    userSelect:   'none',
                }}
                onMouseEnter={e => {
                    if (clickable) {
                        e.currentTarget.style.background  = '#bae0ff';
                        e.currentTarget.style.borderColor = '#4096ff';
                    }
                }}
                onMouseLeave={e => {
                    if (clickable) {
                        e.currentTarget.style.background  = '#e6f4ff';
                        e.currentTarget.style.borderColor = '#91caff';
                    }
                }}
            >
                <FileTextOutlined style={{ fontSize: 11 }} />
                <span>{label}</span>
                {clickable && <LinkOutlined style={{ fontSize: 9, opacity: 0.65 }} />}
            </span>
        </Tooltip>
    );
}

// ─── Confidence + evidence block ──────────────────────────────────────────────

function ConfidenceBlock({
                             score,
                             evidence,
                             fromLibrary,
                             sourceChunkId,
                             sourceDocumentId,
                             onEvidenceClick,
                         }) {
    const band = CONFIDENCE_BAND(score);
    if (!band) return null;
    const cfg = BAND_CFG[band];
    const pct = Math.round((score ?? 0) * 100);

    const sources   = evidence
        ? evidence.split(/[,;]/).map(s => s.trim()).filter(Boolean)
        : [];

    // A chip is clickable when we have either a chunkId (best case) or at
    // least a documentId — enough to open the Evidence Details Modal.
    const hasEvidence = !!(sourceChunkId || sourceDocumentId);

    return (
        <div style={{
            marginTop:    8,
            padding:      '10px 12px',
            borderRadius: 6,
            background:   cfg.bg,
            border:       `1px solid ${cfg.border}`,
            borderLeft:   `3px solid ${cfg.color}`,
        }}>
            {/* Header row */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                <span style={{ fontSize: 13 }}>{cfg.icon}</span>
                <Text strong style={{ fontSize: 12, color: cfg.textColor }}>{cfg.label}</Text>

                <span style={{
                    background:   cfg.color,
                    color:        '#fff',
                    borderRadius: 10,
                    padding:      '0 8px',
                    fontSize:     11,
                    fontWeight:   700,
                    lineHeight:   '18px',
                }}>
                    {pct}%
                </span>

                {fromLibrary && (
                    <Tag
                        color="green"
                        icon={<StarOutlined />}
                        style={{ fontSize: 10, margin: 0 }}
                    >
                        From library
                    </Tag>
                )}
            </div>

            {/* Mini confidence bar */}
            <div style={{ marginBottom: sources.length > 0 ? 8 : 0 }}>
                <div style={{
                    height:       5,
                    borderRadius: 3,
                    background:   '#f0f0f0',
                    overflow:     'hidden',
                }}>
                    <div style={{
                        width:      `${pct}%`,
                        height:     '100%',
                        borderRadius: 3,
                        background: cfg.barColor,
                        transition: 'width 0.6s ease',
                    }} />
                </div>
            </div>

            {/* Evidence source chips — clicking opens Evidence Details Modal */}
            {sources.length > 0 && (
                <div>
                    <Text
                        type="secondary"
                        style={{
                            fontSize:      10,
                            fontWeight:    600,
                            textTransform: 'uppercase',
                            letterSpacing: '0.04em',
                            display:       'block',
                            marginBottom:  5,
                        }}
                    >
                        Evidence source{sources.length > 1 ? 's' : ''}
                    </Text>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                        {sources.map((src, i) => (
                            <SourceChip
                                key={i}
                                label={src}
                                clickable={hasEvidence}
                                onClick={onEvidenceClick}
                            />
                        ))}
                    </div>
                    {hasEvidence && (
                        <Text
                            type="secondary"
                            style={{ fontSize: 10, display: 'block', marginTop: 4 }}
                        >
                            <LinkOutlined style={{ fontSize: 9 }} />{' '}
                            Click source to view retrieved evidence
                        </Text>
                    )}
                </div>
            )}

            {/* Low confidence guidance */}
            {band === 'LOW' && (
                <div style={{
                    marginTop:   8,
                    padding:     '6px 10px',
                    background:  '#fff2f0',
                    borderRadius: 4,
                    border:      '1px solid #ffccc7',
                    display:     'flex',
                    gap:         6,
                    alignItems:  'flex-start',
                }}>
                    <InfoCircleOutlined
                        style={{ color: '#ff4d4f', fontSize: 12, marginTop: 2, flexShrink: 0 }}
                    />
                    <Text style={{ fontSize: 11, color: '#820014', lineHeight: 1.5 }}>
                        No uploaded document covers this topic well.{' '}
                        <a
                            href="/documents/upload"
                            onClick={e => e.stopPropagation()}
                            style={{
                                color: '#820014',
                                textDecoration: 'underline',
                            }}
                        >
                            Upload the relevant policy
                        </a>{' '}
                        to improve confidence.
                    </Text>
                </div>
                )}
</div>
);
}

// ─── Evidence snippet block ───────────────────────────────────────────────────

function EvidenceSnippet({ section, quote }) {
    if (!quote) return null;
    return (
        <div style={{
            marginTop:    6,
            padding:      '8px 12px',
            borderRadius: 4,
            background:   '#fafafa',
            borderLeft:   '3px solid #d9d9d9',
        }}>
            {section && (
                <Text
                    type="secondary"
                    style={{ fontSize: 10, display: 'block', marginBottom: 3, fontWeight: 600 }}
                >
                    {section}
                </Text>
            )}
            <Text italic style={{ fontSize: 12, color: '#595959', lineHeight: 1.55 }}>
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
 *   answer           string   – The answer text (ai_answer or manual_answer)
 *   evidence         string   – Source label(s), e.g. "Security Policy v4"
 *   evidenceSection  string   – Optional section reference
 *   evidenceQuote    string   – Optional verbatim excerpt (rare — usually from EvidenceDetailsModal)
 *   retrievalScore   number   – 0.0–1.0 cosine similarity
 *   status           string   – QuestionStatus enum value
 *   fromLibrary      boolean  – True if reused from approved library
 *   compact          boolean  – Compact table-cell mode
 *   sourceChunkId    UUID     – The stored chunk ID (enables Evidence Details Modal)
 *   sourceDocumentId UUID     – The stored document ID (fallback for View Document)
 */
export default function EvidencePanel({
                                          answer,
                                          evidence,
                                          evidenceSection,
                                          evidenceQuote,
                                          retrievalScore,
                                          status,
                                          fromLibrary      = false,
                                          compact          = false,
                                          sourceChunkId,
                                          sourceDocumentId,
                                      }) {
    const [modalOpen, setModalOpen] = useState(false);

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
        <>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>

                {/* Answer text */}
                <Text style={{ fontSize: 13, lineHeight: 1.65 }}>
                    {answer}
                </Text>

                {/* Status badges */}
                {isEdited && (
                    <Tag color="cyan" style={{ width: 'fit-content', fontSize: 10, marginTop: 2 }}>
                        ✏️ Edited by reviewer
                    </Tag>
                )}
                {isRejected && (
                    <Tag color="error" style={{ width: 'fit-content', fontSize: 10, marginTop: 2 }}>
                        Rejected
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

                {/* Compact mode: plain evidence label + inline confidence */}
                {compact && evidence && evidence !== 'N/A' && (
                    <div style={{ marginTop: 4, display: 'flex', alignItems: 'center', gap: 4 }}>
                        <FileTextOutlined style={{ fontSize: 11, color: '#8c8c8c' }} />
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
                {compact && retrievalScore != null && (
                    <div style={{ marginTop: 2 }}>
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

                {/* Evidence verbatim quote (optional, for pre-populated snippets) */}
                {!compact && evidenceQuote && (
                    <EvidenceSnippet section={evidenceSection} quote={evidenceQuote} />
                )}

                {/* Full confidence block with clickable evidence chips */}
                {!compact && (
                    <ConfidenceBlock
                        score={retrievalScore}
                        evidence={evidence}
                        fromLibrary={fromLibrary}
                        sourceChunkId={sourceChunkId}
                        sourceDocumentId={sourceDocumentId}
                        onEvidenceClick={() => setModalOpen(true)}
                    />
                )}
            </div>

            {/* Evidence Details Modal — rendered here so it's co-located with
                the panel that triggers it. Portal renders it outside the table
                cell so no z-index issues. */}
            <EvidenceDetailsModal
                open={modalOpen}
                onClose={() => setModalOpen(false)}
                chunkId={sourceChunkId}
                documentId={sourceDocumentId}
                evidenceLabel={evidence}
                retrievalScore={retrievalScore}
            />
        </>
    );
}