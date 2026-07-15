// src/components/GenerationProgress.jsx
// Rich generation progress panel shown during AI answer generation.
// Replaces the plain "Generating..." with live step-by-step feedback.

import { useEffect, useState } from 'react';
import { Progress, Typography, Steps, Card, Spin } from 'antd';
import {
    CheckCircleOutlined,
    LoadingOutlined,
    DatabaseOutlined,
    SearchOutlined,
    RobotOutlined,
    SaveOutlined,
    FileDoneOutlined,
} from '@ant-design/icons';

const { Text } = Typography;

// ─── Step definitions ─────────────────────────────────────────────────────────
// Each step has an icon, label, and a description shown when active.
const STEPS = [
    {
        key:     'parse',
        icon:    <FileDoneOutlined />,
        title:   'Documents parsed',
        done:    '✓ Documents parsed',
        active:  'Reading your documents...',
    },
    {
        key:     'index',
        icon:    <DatabaseOutlined />,
        title:   'Knowledge base indexed',
        done:    '✓ Knowledge base indexed',
        active:  'Building searchable index...',
    },
    {
        key:     'search',
        icon:    <SearchOutlined />,
        title:   'Searching knowledge base',
        done:    '✓ Knowledge base searched',
        active:  'Finding relevant evidence...',
    },
    {
        key:     'generate',
        icon:    <RobotOutlined />,
        title:   'Generating answers',
        done:    '✓ Answers generated',
        active:  'Writing answers from evidence...',
    },
    {
        key:     'save',
        icon:    <SaveOutlined />,
        title:   'Preparing review queue',
        done:    '✓ Review queue ready',
        active:  'Saving and organising results...',
    },
];

// ─── Animated sub-status messages ─────────────────────────────────────────────
// Shown beneath the current question counter during generation.
const SUB_MESSAGES = [
    'Searching knowledge base...',
    'Retrieving evidence...',
    'Generating answer...',
    'Checking approved library...',
    'Saving...',
];

// ─── Component ────────────────────────────────────────────────────────────────

/**
 * GenerationProgress
 *
 * Props:
 *   status             string  – 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
 *   totalQuestions     number
 *   completedQuestions number
 *   progressPercent    number  – 0–100
 *   statusMessage      string  – From backend GenerationJobResponse
 *   documentsIndexed   number  – Optional: how many document chunks were indexed
 *   chunksCount        number  – Optional: total chunk count
 */
export default function GenerationProgress({
                                               status,
                                               totalQuestions,
                                               completedQuestions,
                                               progressPercent,
                                               statusMessage,
                                               documentsIndexed,
                                               chunksCount,
                                           }) {
    const [subMsgIdx, setSubMsgIdx] = useState(0);
    const isRunning   = status === 'RUNNING';
    const isCompleted = status === 'COMPLETED';
    const isFailed    = status === 'FAILED';
    const isPending   = status === 'PENDING';

    // Cycle through sub-messages while running
    useEffect(() => {
        if (!isRunning) return;
        const t = setInterval(() => {
            setSubMsgIdx(i => (i + 1) % SUB_MESSAGES.length);
        }, 1400);
        return () => clearInterval(t);
    }, [isRunning]);

    // Derive which step we are on from progress percent
    const activeStep = isCompleted ? 5
        : isPending  ? 0
            : progressPercent < 10  ? 1   // indexing
                : progressPercent < 20  ? 2   // first searches
                    : progressPercent < 95  ? 3   // generating bulk
                        : 4;                           // saving

    const strokeColor = isFailed
        ? '#ff4d4f'
        : isCompleted
            ? '#52c41a'
            : { from: '#108ee9', to: '#87d068' };

    return (
        <Card
            style={{
                marginBottom: 20,
                borderColor: isFailed
                    ? '#ffccc7'
                    : isCompleted
                        ? '#b7eb8f'
                        : '#91caff',
                background: isFailed
                    ? '#fff2f0'
                    : isCompleted
                        ? '#f6ffed'
                        : '#e6f4ff',
            }}
            bodyStyle={{ padding: '18px 22px' }}
        >

            {/* ── Header ──────────────────────────────────────────────────────── */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
                {isRunning && (
                    <Spin
                        indicator={<LoadingOutlined style={{ fontSize: 20, color: '#1890ff' }} spin />}
                    />
                )}
                {isCompleted && (
                    <CheckCircleOutlined style={{ fontSize: 20, color: '#52c41a' }} />
                )}
                {isFailed && (
                    <span style={{ fontSize: 20 }}>❌</span>
                )}
                {isPending && (
                    <RobotOutlined style={{ fontSize: 20, color: '#8c8c8c' }} />
                )}

                <div>
                    <Text strong style={{ fontSize: 15, display: 'block' }}>
                        {isFailed   ? 'Generation failed'
                            : isCompleted ? `Generation complete — ${completedQuestions} answers ready for review`
                                : isPending   ? 'Ready to generate'
                                    : statusMessage || 'Generating answers...'}
                    </Text>
                    {isRunning && (
                        <Text type="secondary" style={{ fontSize: 12 }}>
                            {SUB_MESSAGES[subMsgIdx]}
                        </Text>
                    )}
                </div>
            </div>

            {/* ── Progress bar ─────────────────────────────────────────────────── */}
            {!isPending && (
                <Progress
                    percent={isCompleted ? 100 : progressPercent || 0}
                    status={isFailed ? 'exception' : isCompleted ? 'success' : 'active'}
                    strokeColor={strokeColor}
                    trailColor="rgba(0,0,0,0.06)"
                    format={() => (
                        isCompleted
                            ? <CheckCircleOutlined style={{ color: '#52c41a' }} />
                            : isFailed
                                ? <span style={{ color: '#ff4d4f', fontSize: 12 }}>Failed</span>
                                : (
                                    <span style={{ fontSize: 12, fontWeight: 700, color: '#1677ff' }}>
                    {completedQuestions} / {totalQuestions}
                  </span>
                                )
                    )}
                    style={{ marginBottom: 16 }}
                />
            )}

            {/* ── Pre-generation KB stats (shown before generation starts) ────── */}
            {(isPending || isRunning) && (documentsIndexed || chunksCount) && (
                <div style={{
                    display:        'flex',
                    gap:            24,
                    marginBottom:   16,
                    padding:        '8px 12px',
                    background:     'rgba(255,255,255,0.6)',
                    borderRadius:   6,
                    border:         '1px solid rgba(0,0,0,0.06)',
                }}>
                    {documentsIndexed && (
                        <div>
                            <Text type="secondary" style={{ fontSize: 11 }}>Documents</Text>
                            <div style={{ fontWeight: 700, fontSize: 18, color: '#1890ff', lineHeight: 1.2 }}>
                                {documentsIndexed}
                            </div>
                        </div>
                    )}
                    {chunksCount && (
                        <div>
                            <Text type="secondary" style={{ fontSize: 11 }}>Chunks indexed</Text>
                            <div style={{ fontWeight: 700, fontSize: 18, color: '#1890ff', lineHeight: 1.2 }}>
                                {chunksCount.toLocaleString()}
                            </div>
                        </div>
                    )}
                    {totalQuestions > 0 && (
                        <div>
                            <Text type="secondary" style={{ fontSize: 11 }}>Questions</Text>
                            <div style={{ fontWeight: 700, fontSize: 18, color: '#1890ff', lineHeight: 1.2 }}>
                                {totalQuestions}
                            </div>
                        </div>
                    )}
                </div>
            )}

            {/* ── Step pipeline (shown while running or complete) ─────────────── */}
            {!isPending && (
                <Steps
                    size="small"
                    direction="horizontal"
                    current={activeStep}
                    status={isFailed ? 'error' : 'process'}
                    style={{ marginTop: 4 }}
                    items={STEPS.map((step, idx) => ({
                        title: (
                            <Text style={{
                                fontSize:   11,
                                color:      idx < activeStep
                                    ? '#52c41a'
                                    : idx === activeStep && !isCompleted
                                        ? '#1890ff'
                                        : '#8c8c8c',
                                fontWeight: idx === activeStep ? 600 : 400,
                            }}>
                                {idx < activeStep
                                    ? step.done
                                    : idx === activeStep && isRunning
                                        ? step.active
                                        : step.title}
                            </Text>
                        ),
                        icon: idx < activeStep
                            ? <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 14 }} />
                            : idx === activeStep && isRunning
                                ? <LoadingOutlined style={{ color: '#1890ff', fontSize: 14 }} spin />
                                : undefined,
                    }))}
                />
            )}

            {/* ── Running help text ────────────────────────────────────────────── */}
            {isRunning && (
                <Text
                    type="secondary"
                    style={{ fontSize: 11, display: 'block', marginTop: 10, textAlign: 'center' }}
                >
                    Answers appear in the review table as they are generated. Large questionnaires take 5–15 minutes.
                </Text>
            )}
        </Card>
    );
}