// src/components/KnowledgeBaseReady.jsx
// Shown on the Dashboard / Documents page after documents have been processed.
// Replaces the empty state with a "Knowledge Base Ready" summary that
// immediately communicates value.

import { useEffect, useState } from 'react';
import { Card, Col, Row, Typography, Button, Tag, Spin } from 'antd';
import {
    CheckCircleOutlined,
    FileTextOutlined,
    DatabaseOutlined,
    ThunderboltOutlined,
    UploadOutlined,
} from '@ant-design/icons';
import { Link } from 'react-router-dom';

const { Title, Text } = Typography;

// ─── Animated counter ─────────────────────────────────────────────────────────
// Counts up from 0 to target over ~800ms for visual impact.

function AnimatedCount({ target, suffix = '', style = {} }) {
    const [value, setValue] = useState(0);

    useEffect(() => {
        if (!target) return;
        let start   = 0;
        const steps = 40;
        const step  = target / steps;
        const interval = setInterval(() => {
            start += step;
            if (start >= target) {
                setValue(target);
                clearInterval(interval);
            } else {
                setValue(Math.floor(start));
            }
        }, 20);
        return () => clearInterval(interval);
    }, [target]);

    return (
        <span style={style}>
      {value.toLocaleString()}{suffix}
    </span>
    );
}

// ─── Stat block ───────────────────────────────────────────────────────────────

function StatBlock({ value, label, icon, color = '#1890ff', animate = true }) {
    return (
        <div style={{ textAlign: 'center' }}>
            <div style={{ fontSize: 16, marginBottom: 4 }}>
                {icon}
            </div>
            <div style={{
                fontSize:   32,
                fontWeight: 800,
                color,
                lineHeight: 1,
                marginBottom: 4,
            }}>
                {animate
                    ? <AnimatedCount target={value} />
                    : value?.toLocaleString() ?? '—'
                }
            </div>
            <Text type="secondary" style={{ fontSize: 12 }}>{label}</Text>
        </div>
    );
}

// ─── Main component ───────────────────────────────────────────────────────────

/**
 * KnowledgeBaseReady
 *
 * Props:
 *   documentCount    number  – Total indexed documents
 *   pageCount        number  – Estimated total pages (optional)
 *   chunkCount       number  – Total indexed chunks
 *   readyCount       number  – Documents with READY status
 *   processingCount  number  – Documents still processing
 *   approvedAnswers  number  – Answers saved to organizational memory (NEW)
 *   loading          bool    – True while fetching stats
 */
export default function KnowledgeBaseReady({
                                               documentCount    = 0,
                                               pageCount,
                                               chunkCount       = 0,
                                               readyCount       = 0,
                                               processingCount  = 0,
                                               approvedAnswers  = 0,
                                               loading          = false,
                                           }) {
    const hasDocuments = documentCount > 0;
    const allReady     = processingCount === 0 && readyCount > 0;

    if (loading) {
        return (
            <Card style={{ textAlign: 'center', padding: 40 }}>
                <Spin size="large" />
                <div style={{ marginTop: 16 }}>
                    <Text type="secondary">Loading knowledge base stats...</Text>
                </div>
            </Card>
        );
    }

    if (!hasDocuments) {
        // Empty state — invite first upload
        return (
            <Card
                style={{
                    borderRadius: 12,
                    border:       '1px dashed #d9d9d9',
                    background:   '#fafafa',
                    textAlign:    'center',
                    padding:      '32px 24px',
                }}
                bodyStyle={{ padding: '32px 24px' }}
            >
                <DatabaseOutlined style={{ fontSize: 40, color: '#d9d9d9', marginBottom: 16 }} />
                <Title level={4} style={{ color: '#8c8c8c', marginBottom: 8 }}>
                    No documents yet
                </Title>
                <Text type="secondary" style={{ display: 'block', marginBottom: 20 }}>
                    Upload your security policies, SOC 2 reports, and other documents
                    to build your AI knowledge base.
                </Text>
                <Link to="/documents/upload">
                    <Button type="primary" icon={<UploadOutlined />} size="large">
                        Upload your first document
                    </Button>
                </Link>
            </Card>
        );
    }

    return (
        <Card
            style={{
                borderRadius: 12,
                border:       allReady ? '1px solid #b7eb8f' : '1px solid #91caff',
                background:   allReady
                    ? 'linear-gradient(135deg, #f6ffed 0%, #ffffff 70%)'
                    : 'linear-gradient(135deg, #e6f4ff 0%, #ffffff 70%)',
                overflow:     'hidden',
            }}
            bodyStyle={{ padding: '24px 28px' }}
        >

            {/* ── Status header ────────────────────────────────────────────────── */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 20 }}>
                {allReady
                    ? <CheckCircleOutlined style={{ fontSize: 22, color: '#52c41a' }} />
                    : <Spin size="small" />
                }
                <Title level={4} style={{ margin: 0, color: allReady ? '#135200' : '#0958d9' }}>
                    {allReady ? 'Knowledge Base Ready' : 'Building Knowledge Base...'}
                </Title>
                {processingCount > 0 && (
                    <Tag color="processing" style={{ fontSize: 11 }}>
                        {processingCount} indexing
                    </Tag>
                )}
            </div>

            // AFTER
            {/* ── Stats grid ───────────────────────────────────────────────────── */}
            <Row gutter={0} style={{
                marginBottom: 20,
                padding:      '16px 0',
                borderTop:    '1px solid rgba(0,0,0,0.06)',
                borderBottom: '1px solid rgba(0,0,0,0.06)',
            }}>
                {/* Column widths: 3 cols → 8 each; 4 cols when approvedAnswers > 0 → 6 each */}
                {(() => {
                    const showApproved = approvedAnswers > 0;
                    const colSpan      = showApproved ? 6 : 8;
                    const divider      = { borderRight: '1px solid rgba(0,0,0,0.06)' };
                    return (
                        <>
                            <Col span={colSpan} style={{ ...divider, paddingRight: 16 }}>
                                <StatBlock
                                    value={documentCount}
                                    label="Documents"
                                    icon={<FileTextOutlined style={{ color: '#1890ff', fontSize: 18 }} />}
                                    color="#1890ff"
                                />
                            </Col>

                            {pageCount != null && (
                                <Col span={colSpan} style={{ ...divider, paddingLeft: 16, paddingRight: 16 }}>
                                    <StatBlock
                                        value={pageCount}
                                        label="Pages Processed"
                                        icon={<span style={{ fontSize: 18 }}>📄</span>}
                                        color="#722ed1"
                                    />
                                </Col>
                            )}

                            <Col
                                span={colSpan}
                                style={{
                                    paddingLeft:  16,
                                    paddingRight: showApproved ? 16 : 0,
                                    ...(showApproved ? divider : {}),
                                }}
                            >
                                <StatBlock
                                    value={chunkCount}
                                    label="Knowledge Chunks"
                                    icon={<DatabaseOutlined style={{ color: '#52c41a', fontSize: 18 }} />}
                                    color="#52c41a"
                                />
                            </Col>

                            {/* NEW — only shown once organisation has built up memory */}
                            {showApproved && (
                                <Col span={colSpan} style={{ paddingLeft: 16 }}>
                                    <StatBlock
                                        value={approvedAnswers}
                                        label="Security Memory"
                                        icon={<span style={{ fontSize: 18 }}>🧠</span>}
                                        color="#eb2f96"
                                    />
                                </Col>
                            )}
                        </>
                    );
                })()}
            </Row>

            {/* ── Ready state message ───────────────────────────────────────────── */}
            // AFTER
            {allReady && (
                <div style={{
                    display:      'flex',
                    alignItems:   'center',
                    gap:          12,
                    padding:      '12px 16px',
                    background:   '#fff',
                    borderRadius: 8,
                    border:       '1px solid #f0f0f0',
                    marginBottom: 16,
                }}>
                    <ThunderboltOutlined style={{ fontSize: 18, color: '#52c41a' }} />
                    <div>
                        <Text strong style={{ fontSize: 13, display: 'block' }}>
                            Ready for questionnaires
                        </Text>
                        <Text type="secondary" style={{ fontSize: 12 }}>
                            {approvedAnswers > 0
                                ? `The AI can search ${chunkCount.toLocaleString()} knowledge chunks and reuse ${approvedAnswers.toLocaleString()} approved answers from organizational security memory.`
                                : `The AI can now search ${chunkCount.toLocaleString()} knowledge chunks to answer security questionnaires.`
                            }
                        </Text>
                    </div>
                </div>
            )}

            {/* ── CTA ──────────────────────────────────────────────────────────── */}
            <div style={{ display: 'flex', gap: 10 }}>
                <Link to="/questionnaires/upload" style={{ flex: 1 }}>
                    <Button
                        type="primary"
                        block
                        icon={<ThunderboltOutlined />}
                        disabled={!allReady}
                        size="large"
                    >
                        Answer a Questionnaire
                    </Button>
                </Link>
                <Link to="/documents/upload">
                    <Button icon={<UploadOutlined />} size="large">
                        Add Documents
                    </Button>
                </Link>
            </div>
        </Card>
    );
}