// src/components/QuestionnaireSummary.jsx
// Post-generation summary panel that quantifies the value delivered.
// Shows total questions, answers, needs-review count, missing evidence,
// and a calculated estimated manual review time.

import { Card, Col, Row, Statistic, Tag, Typography, Button, Divider } from 'antd';
import {
    CheckCircleOutlined,
    ClockCircleOutlined,
    ExclamationCircleOutlined,
    DownloadOutlined,
    EyeOutlined,
    FileTextOutlined,
    ThunderboltOutlined,
} from '@ant-design/icons';

const { Title, Text, Paragraph } = Typography;

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Estimate manual review time.
 *
 * Heuristic (tuned to be defensible):
 *   - Each approved answer (high confidence):  15s  to skim + approve
 *   - Each needs-review answer (medium/low):   90s  to read, assess, possibly edit
 *   - Each missing-evidence question:         180s  to write from scratch
 *
 * Returned as a human-readable string, e.g. "≈35 minutes" or "1–2 hours".
 */
function estimateReviewTime(approved, needsReview, noEvidence) {
    const seconds =
        (approved    ?? 0) * 15 +
        (needsReview ?? 0) * 90 +
        (noEvidence  ?? 0) * 180;

    const minutes = Math.round(seconds / 60);

    if (minutes < 5)   return '< 5 minutes';
    if (minutes < 60)  return `≈${Math.round(minutes / 5) * 5} minutes`;
    const hours = seconds / 3600;
    if (hours < 2)     return '1–2 hours';
    return `${Math.round(hours)}+ hours`;
}

function estimateManualBaseline(total) {
    // Baseline: doing this entirely by hand at 5 min/question
    const minutes = total * 5;
    if (minutes < 60) return `${minutes} minutes`;
    const hours = Math.round(minutes / 60);
    return `${hours}–${hours + 2} hours`;
}

// ─── Metric tile ──────────────────────────────────────────────────────────────

function MetricTile({ value, label, color, icon, bg, border }) {
    return (
        <div style={{
            textAlign:    'center',
            padding:      '16px 12px',
            borderRadius: 10,
            background:   bg ?? '#fafafa',
            border:       `1px solid ${border ?? '#f0f0f0'}`,
            flex:         1,
        }}>
            <div style={{ fontSize: 22, marginBottom: 2 }}>{icon}</div>
            <div style={{
                fontSize:   28,
                fontWeight: 800,
                color:      color ?? '#262626',
                lineHeight: 1.1,
            }}>
                {value}
            </div>
            <Text type="secondary" style={{ fontSize: 12 }}>{label}</Text>
        </div>
    );
}

// ─── Main component ───────────────────────────────────────────────────────────

/**
 * QuestionnaireSummary
 *
 * Props:
 *   totalQuestions     number
 *   aiAnswered         number  – questions that received an AI answer (GENERATED + APPROVED + EDITED)
 *   needsReview        number  – GENERATED answers still awaiting human review
 *   noEvidence         number  – PENDING after generation (no relevant chunks found)
 *   approvedCount      number  – already APPROVED (high confidence)
 *   editedCount        number
 *   rejectedCount      number
 *   filename           string  – questionnaire file name for display
 *   onExport           fn      – called when "Export Excel" is clicked
 *   onReview           fn      – called when "Review Answers" is clicked
 *   canExport          bool
 */
export default function QuestionnaireSummary({
                                                 totalQuestions = 0,
                                                 aiAnswered     = 0,
                                                 needsReview    = 0,
                                                 noEvidence     = 0,
                                                 approvedCount  = 0,
                                                 editedCount    = 0,
                                                 rejectedCount  = 0,
                                                 filename,
                                                 onExport,
                                                 onReview,
                                                 canExport = true,
                                             }) {
    const reviewTime    = estimateReviewTime(approvedCount + editedCount, needsReview, noEvidence);
    const baselineTime  = estimateManualBaseline(totalQuestions);
    const aiCoverage    = totalQuestions > 0
        ? Math.round((aiAnswered / totalQuestions) * 100)
        : 0;

    return (
        <Card
            style={{
                borderRadius: 12,
                border:       '1px solid #b7eb8f',
                background:   'linear-gradient(135deg, #f6ffed 0%, #ffffff 60%)',
                overflow:     'hidden',
            }}
            bodyStyle={{ padding: '24px 28px' }}
        >

            {/* ── Header ──────────────────────────────────────────────────────── */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
                <ThunderboltOutlined style={{ fontSize: 20, color: '#52c41a' }} />
                <Title level={4} style={{ margin: 0, color: '#135200' }}>
                    Questionnaire Complete
                </Title>
            </div>
            {filename && (
                <Text type="secondary" style={{ fontSize: 13, display: 'block', marginBottom: 20 }}>
                    {filename}
                </Text>
            )}

            {/* ── Metric tiles ─────────────────────────────────────────────────── */}
            <div style={{ display: 'flex', gap: 12, marginBottom: 20, flexWrap: 'wrap' }}>
                <MetricTile
                    value={totalQuestions}
                    label="Total Questions"
                    color="#1890ff"
                    bg="#e6f4ff"
                    border="#91caff"
                    icon={<FileTextOutlined style={{ color: '#1890ff' }} />}
                />
                <MetricTile
                    value={aiAnswered}
                    label="AI Answered"
                    color="#52c41a"
                    bg="#f6ffed"
                    border="#b7eb8f"
                    icon={<CheckCircleOutlined style={{ color: '#52c41a' }} />}
                />
                <MetricTile
                    value={needsReview}
                    label="Needs Review"
                    color="#faad14"
                    bg="#fffbe6"
                    border="#ffe58f"
                    icon="⚠️"
                />
                <MetricTile
                    value={noEvidence}
                    label="No Evidence"
                    color="#ff4d4f"
                    bg="#fff2f0"
                    border="#ffccc7"
                    icon={<ExclamationCircleOutlined style={{ color: '#ff4d4f' }} />}
                />
            </div>

            {/* ── Time savings block ───────────────────────────────────────────── */}
            <div style={{
                display:        'flex',
                gap:            0,
                borderRadius:   10,
                border:         '1px solid #f0f0f0',
                overflow:       'hidden',
                marginBottom:   20,
                background:     '#fff',
            }}>
                {/* Estimated manual work remaining */}
                <div style={{
                    flex:       1,
                    padding:    '14px 18px',
                    borderRight: '1px solid #f0f0f0',
                    textAlign:  'center',
                }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, justifyContent: 'center', marginBottom: 4 }}>
                        <ClockCircleOutlined style={{ color: '#faad14' }} />
                        <Text strong style={{ fontSize: 13 }}>Estimated manual work remaining</Text>
                    </div>
                    <div style={{ fontSize: 30, fontWeight: 800, color: '#faad14', lineHeight: 1 }}>
                        {reviewTime}
                    </div>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                        To review and approve AI answers
                    </Text>
                </div>

                {/* Baseline comparison */}
                <div style={{
                    flex:       1,
                    padding:    '14px 18px',
                    textAlign:  'center',
                    background: '#fafafa',
                }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, justifyContent: 'center', marginBottom: 4 }}>
                        <span style={{ fontSize: 14 }}>🐌</span>
                        <Text strong style={{ fontSize: 13, color: '#8c8c8c' }}>Manual baseline</Text>
                    </div>
                    <div style={{ fontSize: 30, fontWeight: 800, color: '#8c8c8c', lineHeight: 1 }}>
                        {baselineTime}
                    </div>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                        Answering all {totalQuestions} questions by hand
                    </Text>
                </div>
            </div>

            {/* ── AI coverage bar ──────────────────────────────────────────────── */}
            <div style={{ marginBottom: 20 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                    <Text style={{ fontSize: 12, fontWeight: 600 }}>AI Coverage</Text>
                    <Text style={{ fontSize: 12, fontWeight: 700, color: '#52c41a' }}>
                        {aiCoverage}% answered by AI
                    </Text>
                </div>
                <div style={{
                    height:       10,
                    borderRadius: 5,
                    background:   '#f0f0f0',
                    overflow:     'hidden',
                }}>
                    <div style={{
                        width:        `${aiCoverage}%`,
                        height:       '100%',
                        borderRadius: 5,
                        background:   'linear-gradient(90deg, #52c41a 0%, #73d13d 100%)',
                        transition:   'width 1s ease',
                    }} />
                </div>
                <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 4 }}>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                        {aiAnswered} of {totalQuestions} questions
                    </Text>
                </div>
            </div>

            {/* ── Quick stats breakdown ────────────────────────────────────────── */}
            <div style={{
                display:        'flex',
                gap:            16,
                padding:        '10px 14px',
                background:     '#fafafa',
                borderRadius:   8,
                marginBottom:   20,
                flexWrap:       'wrap',
            }}>
                {[
                    { label: 'Approved',    value: approvedCount,  color: '#52c41a' },
                    { label: 'Edited',      value: editedCount,    color: '#13c2c2' },
                    { label: 'Rejected',    value: rejectedCount,  color: '#ff4d4f' },
                    { label: 'Needs Review',value: needsReview,    color: '#faad14' },
                    { label: 'No Evidence', value: noEvidence,     color: '#8c8c8c' },
                ].map(({ label, value, color }) => (
                    <div key={label}>
                        <span style={{ fontWeight: 700, color, fontSize: 15 }}>{value}</span>
                        {' '}
                        <Text type="secondary" style={{ fontSize: 12 }}>{label}</Text>
                    </div>
                ))}
            </div>

            {/* ── Actions ──────────────────────────────────────────────────────── */}
            <div style={{ display: 'flex', gap: 10 }}>
                <Button
                    type="primary"
                    size="large"
                    icon={<EyeOutlined />}
                    onClick={onReview}
                    style={{ flex: 1 }}
                >
                    Review Answers
                </Button>
                <Button
                    size="large"
                    icon={<DownloadOutlined />}
                    onClick={onExport}
                    disabled={!canExport}
                    style={{ flex: 1 }}
                >
                    Export Excel
                </Button>
            </div>

            {noEvidence > 0 && (
                <Text
                    type="secondary"
                    style={{ fontSize: 11, display: 'block', textAlign: 'center', marginTop: 10 }}
                >
                    💡 {noEvidence} questions have no matching evidence.{' '}
                    <a href="/documents/upload">Upload relevant policies</a> to improve coverage.
                </Text>
            )}
        </Card>
    );
}