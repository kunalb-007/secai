// NEW FILE: src/components/EvidenceDetailsModal.jsx

import { useEffect, useState } from 'react';
import { Modal, Button, Typography, Spin, Alert, Space, Tag, Divider } from 'antd';
import {
    FileTextOutlined,
    LinkOutlined,
    InfoCircleOutlined,
    ExportOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { getChunk } from '../api/chunks';

const { Text, Paragraph } = Typography;

// ─── Confidence band helper ───────────────────────────────────────────────────

const confidenceBand = (score) => {
    if (score == null) return null;
    if (score >= 0.85) return { label: 'High',   color: '#52c41a', bg: '#f6ffed', border: '#b7eb8f', textColor: '#135200' };
    if (score >= 0.60) return { label: 'Medium', color: '#faad14', bg: '#fffbe6', border: '#ffe58f', textColor: '#614700' };
    return                     { label: 'Low',    color: '#ff4d4f', bg: '#fff2f0', border: '#ffccc7', textColor: '#820014' };
};

// ─── Evidence Details Modal ───────────────────────────────────────────────────

/**
 * EvidenceDetailsModal
 *
 * Props:
 *   open            boolean  – controls visibility
 *   onClose         fn       – called when modal closes
 *   chunkId         UUID     – the sourceChunkId stored on the question
 *   documentId      UUID     – the sourceDocumentId stored on the question
 *   evidenceLabel   string   – the evidence string (e.g. "Internal Procedures")
 *   retrievalScore  number   – 0.0–1.0 similarity score
 *
 * Fetches chunk text from GET /api/chunks/:chunkId on open.
 * Does NOT perform a new vector search.
 */
export default function EvidenceDetailsModal({
                                                 open,
                                                 onClose,
                                                 chunkId,
                                                 documentId,
                                                 evidenceLabel,
                                                 retrievalScore,
                                             }) {
    const navigate          = useNavigate();
    const [chunk, setChunk] = useState(null);
    const [loading, setLoading] = useState(false);
    const [error, setError]     = useState('');

    // Fetch chunk text whenever the modal opens with a valid chunkId
    useEffect(() => {
        if (!open || !chunkId) {
            setChunk(null);
            setError('');
            return;
        }

        let cancelled = false;
        setLoading(true);
        setError('');
        setChunk(null);

        getChunk(chunkId)
            .then((res) => {
                if (!cancelled) setChunk(res.data);
            })
            .catch(() => {
                if (!cancelled) setError('Could not load evidence details. The source chunk may have been deleted.');
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });

        return () => { cancelled = true; };
    }, [open, chunkId]);

    const band         = confidenceBand(retrievalScore);
    const pct          = retrievalScore != null ? Math.round(retrievalScore * 100) : null;
    const displayName  = chunk?.documentFilename ?? evidenceLabel ?? 'Source document';
    const sectionTitle = chunk?.sectionTitle;
    const chunkText    = chunk?.text;

    const handleViewDocument = () => {
        const targetDocId = chunk?.documentId ?? documentId;
        if (targetDocId) {
            onClose();
            navigate(`/documents/${targetDocId}`);
        }
    };

    // ── No chunk ID at all — evidence label only ──────────────────────────────
    const noChunkAvailable = !chunkId && open;

    return (
        <Modal
            open={open}
            onCancel={onClose}
            footer={null}
            width={620}
            title={
                <Space size={8}>
                    <FileTextOutlined style={{ color: '#1890ff', fontSize: 16 }} />
                    <span style={{ fontSize: 15, fontWeight: 600 }}>Evidence Details</span>
                </Space>
            }
            destroyOnClose
        >
            <div style={{ display: 'flex', flexDirection: 'column', gap: 16, paddingTop: 4 }}>

                {/* ── Document name header ──────────────────────────────────────── */}
                <div style={{
                    display:      'flex',
                    alignItems:   'center',
                    gap:          10,
                    padding:      '10px 14px',
                    background:   '#f5f5f5',
                    borderRadius: 8,
                    border:       '1px solid #e8e8e8',
                }}>
                    <FileTextOutlined style={{ fontSize: 20, color: '#1890ff', flexShrink: 0 }} />
                    <div style={{ flex: 1, minWidth: 0 }}>
                        <Text strong style={{ fontSize: 14, display: 'block', lineHeight: 1.4 }}>
                            {displayName}
                        </Text>
                        {sectionTitle && (
                            <Text type="secondary" style={{ fontSize: 12 }}>
                                {sectionTitle}
                            </Text>
                        )}
                    </div>
                    {pct != null && band && (
                        <span style={{
                            background:   band.color,
                            color:        '#fff',
                            borderRadius: 10,
                            padding:      '2px 10px',
                            fontSize:     12,
                            fontWeight:   700,
                            flexShrink:   0,
                        }}>
                            {pct}% match
                        </span>
                    )}
                </div>

                {/* ── Loading state ─────────────────────────────────────────────── */}
                {loading && (
                    <div style={{ textAlign: 'center', padding: '28px 0' }}>
                        <Spin size="default" />
                        <Text type="secondary" style={{ display: 'block', marginTop: 10, fontSize: 13 }}>
                            Loading evidence snippet…
                        </Text>
                    </div>
                )}

                {/* ── Error state ───────────────────────────────────────────────── */}
                {!loading && error && (
                    <Alert
                        type="warning"
                        showIcon
                        message="Evidence snippet unavailable"
                        description={error}
                        style={{ marginBottom: 0 }}
                    />
                )}

                {/* ── No chunkId stored (older questions pre-dating this feature) ─ */}
                {!loading && noChunkAvailable && (
                    <Alert
                        type="info"
                        showIcon
                        message="Snippet not stored"
                        description={
                            <span>
                                This answer was generated before evidence snippets were saved.
                                Re-generate the questionnaire to enable evidence details for all questions.
                                {(documentId) && (
                                    <span>
                                        {' '}You can still{' '}
                                        <a onClick={handleViewDocument} style={{ cursor: 'pointer' }}>
                                            view the source document
                                        </a>.
                                    </span>
                                )}
                            </span>
                        }
                    />
                )}

                {/* ── Retrieved evidence snippet ────────────────────────────────── */}
                {!loading && !error && chunkText && (
                    <div>
                        <Text
                            type="secondary"
                            style={{
                                fontSize:      10,
                                fontWeight:    600,
                                textTransform: 'uppercase',
                                letterSpacing: '0.06em',
                                display:       'block',
                                marginBottom:  8,
                            }}
                        >
                            Retrieved evidence
                        </Text>

                        {/* The actual passage the AI read */}
                        <div style={{
                            padding:      '14px 16px',
                            background:   '#fafafa',
                            borderRadius: 6,
                            border:       '1px solid #e8e8e8',
                            borderLeft:   '3px solid #1890ff',
                        }}>
                            <Paragraph
                                style={{
                                    fontSize:   13,
                                    lineHeight: 1.7,
                                    margin:     0,
                                    color:      '#262626',
                                    fontStyle:  'italic',
                                }}
                            >
                                "{chunkText.length > 600
                                ? chunkText.slice(0, 600) + '…'
                                : chunkText}"
                            </Paragraph>
                        </div>

                        {/* Explanation of why this was retrieved */}
                        <div style={{
                            display:      'flex',
                            gap:          6,
                            alignItems:   'flex-start',
                            marginTop:    10,
                            padding:      '8px 12px',
                            background:   '#e6f4ff',
                            borderRadius: 6,
                            border:       '1px solid #91caff',
                        }}>
                            <InfoCircleOutlined style={{ color: '#1890ff', fontSize: 13, marginTop: 2, flexShrink: 0 }} />
                            <Text style={{ fontSize: 12, color: '#0958d9', lineHeight: 1.5 }}>
                                This passage was retrieved because it contains evidence supporting the generated answer.
                                {pct != null && (
                                    <span>
                                        {' '}Semantic similarity to the question:{' '}
                                        <strong>{pct}%</strong>.
                                    </span>
                                )}
                            </Text>
                        </div>
                    </div>
                )}

                {/* ── Confidence band detail (when chunk loaded) ────────────────── */}
                {!loading && !error && chunk && band && (
                    <div style={{
                        padding:      '10px 14px',
                        background:   band.bg,
                        borderRadius: 6,
                        border:       `1px solid ${band.border}`,
                        borderLeft:   `3px solid ${band.color}`,
                        display:      'flex',
                        alignItems:   'center',
                        gap:          10,
                    }}>
                        <div style={{
                            width:        8,
                            height:       8,
                            borderRadius: '50%',
                            background:   band.color,
                            flexShrink:   0,
                        }} />
                        <Text style={{ fontSize: 12, color: band.textColor }}>
                            <strong>{band.label} confidence</strong>
                            {' '}— the AI is{' '}
                            {band.label === 'High'   && 'highly confident this evidence directly answers the question.'}
                            {band.label === 'Medium' && 'moderately confident. Review the snippet to verify the answer.'}
                            {band.label === 'Low'    && 'not confident. The answer may be inaccurate — consider uploading a more relevant document.'}
                        </Text>
                    </div>
                )}

                <Divider style={{ margin: '4px 0' }} />

                {/* ── Footer actions ────────────────────────────────────────────── */}
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                        {chunk?.documentFilename && (
                            <span>Source: <strong>{chunk.documentFilename}</strong></span>
                        )}
                    </Text>
                    <Space size={8}>
                        <Button onClick={onClose}>
                            Close
                        </Button>
                        {(chunk?.documentId ?? documentId) && (
                            <Button
                                type="primary"
                                icon={<ExportOutlined />}
                                onClick={handleViewDocument}
                            >
                                View Full Document
                            </Button>
                        )}
                    </Space>
                </div>

            </div>
        </Modal>
    );
}