// src/pages/CoverageAnalysisPage.jsx
import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
    Alert, Button, Card, Col, Progress, Row, Spin,
    Statistic, Tag, Tooltip, Typography, Steps, List,
    Divider, Space, message,
} from 'antd';
import {
    ArrowLeftOutlined, CheckCircleOutlined, WarningOutlined,
    ExclamationCircleOutlined, FileTextOutlined, UploadOutlined,
    ThunderboltOutlined, ReloadOutlined, InfoCircleOutlined,
} from '@ant-design/icons';
import AppLayout from '../components/AppLayout';
import { CoverageBar, CoverageTierTag } from '../components/CoverageGauge';
import { refreshCoverage }               from '../api/coverage';
import { useCoveragePoller }             from '../hooks/useCoveragePoller';
import { useNavigate as useNav }         from 'react-router-dom';

const { Title, Text, Paragraph } = Typography;

// ─────────────────────────────────────────────────────────────────────────────
// Category row
// ─────────────────────────────────────────────────────────────────────────────

const TIER_ICON = {
    HIGH:     <CheckCircleOutlined style={{ color: '#52c41a' }} />,
    MEDIUM:   <CheckCircleOutlined style={{ color: '#73d13d' }} />,
    LOW:      <WarningOutlined     style={{ color: '#faad14' }} />,
    CRITICAL: <ExclamationCircleOutlined style={{ color: '#ff4d4f' }} />,
};

const TIER_BAR_COLOR = {
    HIGH:     '#52c41a',
    MEDIUM:   '#73d13d',
    LOW:      '#faad14',
    CRITICAL: '#ff4d4f',
};

function CategoryRow({ cat }) {
    return (
        <div style={{
            padding: '12px 0',
            borderBottom: '1px solid #f0f0f0',
        }}>
            <div style={{
                display: 'flex', justifyContent: 'space-between',
                alignItems: 'center', marginBottom: 6,
            }}>
                <Space size={8}>
                    {TIER_ICON[cat.tier] || TIER_ICON.LOW}
                    <Text strong style={{ fontSize: 13 }}>{cat.category}</Text>
                    {cat.missingDocSuggestion && (
                        <Tooltip title={`Consider uploading: ${cat.missingDocSuggestion}`}>
                            <InfoCircleOutlined style={{ color: '#faad14', cursor: 'pointer' }} />
                        </Tooltip>
                    )}
                </Space>
                <Space size={12}>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                        {cat.answerableQuestions}/{cat.totalQuestions} questions
                    </Text>
                    <Text strong style={{ fontSize: 13, color: TIER_BAR_COLOR[cat.tier] }}>
                        {cat.coveragePercent}%
                    </Text>
                </Space>
            </div>
            <Progress
                percent={cat.coveragePercent}
                strokeColor={TIER_BAR_COLOR[cat.tier]}
                trailColor="#f5f5f5"
                showInfo={false}
                size="small"
            />
            {cat.missingDocSuggestion && cat.tier !== 'HIGH' && cat.tier !== 'MEDIUM' && (
                <Text type="secondary" style={{ fontSize: 11, marginTop: 4, display: 'block' }}>
                    📄 Suggested: {cat.missingDocSuggestion}
                </Text>
            )}
        </div>
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Main Page
// ─────────────────────────────────────────────────────────────────────────────

export default function CoverageAnalysisPage() {
    const { id }   = useParams();
    const navigate = useNavigate();

    const [refreshing, setRefreshing] = useState(false);

    const onComplete = useCallback(() => {
        setRefreshing(false);
    }, []);

    const { report, isPolling } = useCoveragePoller(id, onComplete);

    const handleRefresh = async () => {
        setRefreshing(true);
        try {
            await refreshCoverage(id);
            message.info('Re-running coverage analysis…');
        } catch {
            setRefreshing(false);
            message.error('Could not refresh analysis. Please try again.');
        }
    };

    const isRunning  = isPolling || refreshing || report?.status === 'RUNNING' || report?.status === 'PENDING';
    const isComplete = report?.status === 'COMPLETE';
    const isFailed   = report?.status === 'FAILED';

    // ── Loading / pending ─────────────────────────────────────────────────────
    if (!report || isRunning) {
        return (
            <AppLayout>
                <div style={{ maxWidth: 860, margin: '48px auto', padding: '0 24px' }}>
                    <Button type="text" icon={<ArrowLeftOutlined />}
                            onClick={() => navigate(`/questionnaires/${id}`)}
                            style={{ paddingLeft: 0, marginBottom: 20 }}>
                        Back to Questionnaire
                    </Button>

                    <Card style={{ textAlign: 'center', padding: '40px 24px' }}>
                        <Spin size="large" />
                        <Title level={4} style={{ marginTop: 20, marginBottom: 8 }}>
                            Analysing Knowledge Base Coverage
                        </Title>
                        <Text type="secondary" style={{ display: 'block', marginBottom: 24 }}>
                            Checking which questions your current documents can answer.
                            This takes 20–60 seconds.
                        </Text>
                        <Steps
                            size="small"
                            current={1}
                            style={{ maxWidth: 520, margin: '0 auto' }}
                            items={[
                                { title: 'Questions loaded' },
                                { title: 'Sampling & searching KB', status: 'process' },
                                { title: 'Identifying gaps' },
                                { title: 'Report ready' },
                            ]}
                        />
                    </Card>
                </div>
            </AppLayout>
        );
    }

    // ── Failed ────────────────────────────────────────────────────────────────
    if (isFailed) {
        return (
            <AppLayout>
                <div style={{ maxWidth: 860, margin: '48px auto', padding: '0 24px' }}>
                    <Alert
                        type="error"
                        showIcon
                        message="Coverage analysis failed"
                        description="We couldn't analyse your knowledge base. You can still generate answers, or try refreshing the analysis."
                        action={
                            <Space>
                                <Button size="small" onClick={handleRefresh}>Try again</Button>
                                <Button size="small" type="primary"
                                        onClick={() => navigate(`/questionnaires/${id}`)}>
                                    Continue anyway
                                </Button>
                            </Space>
                        }
                    />
                </div>
            </AppLayout>
        );
    }

    // ── Complete ──────────────────────────────────────────────────────────────

    const {
        overallPercent, coverageTier, summary,
        answerableQuestions, unanswerable, totalQuestions,
        categories = [], missingDocSuggestions = [],
        estimatedPercentAfter,
    } = report;

    const hasMissingDocs   = missingDocSuggestions.length > 0;
    const improvementDelta = estimatedPercentAfter - overallPercent;

    return (
        <AppLayout>
            <div style={{ maxWidth: 900, margin: '0 auto', padding: '28px 24px 48px' }}>

                {/* Header */}
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 24 }}>
                    <div>
                        <Button type="text" icon={<ArrowLeftOutlined />}
                                onClick={() => navigate(`/questionnaires/${id}`)}
                                style={{ paddingLeft: 0, marginBottom: 6 }}>
                            Back to Questionnaire
                        </Button>
                        <Title level={3} style={{ margin: 0 }}>Knowledge Base Coverage</Title>
                        <Text type="secondary">{summary}</Text>
                    </div>
                    <Button icon={<ReloadOutlined />} onClick={handleRefresh} loading={refreshing}>
                        Re-check coverage
                    </Button>
                </div>

                {/* ── Hero coverage card ────────────────────────────────────────── */}
                <Card style={{
                    marginBottom: 20,
                    borderColor: overallPercent >= 75 ? '#b7eb8f'
                        : overallPercent >= 55 ? '#ffe58f' : '#ffccc7',
                    background: overallPercent >= 75 ? '#f6ffed'
                        : overallPercent >= 55 ? '#fffbe6' : '#fff2f0',
                }}>
                    <Row gutter={32} align="middle">
                        <Col xs={24} sm={10}>
                            <div style={{ textAlign: 'center' }}>
                                <div style={{ fontSize: 64, fontWeight: 800, lineHeight: 1,
                                    color: overallPercent >= 75 ? '#52c41a'
                                        : overallPercent >= 55 ? '#faad14' : '#ff4d4f' }}>
                                    {overallPercent}%
                                </div>
                                <CoverageTierTag tier={coverageTier} style={{ marginTop: 8, fontSize: 13 }} />
                            </div>
                        </Col>
                        <Col xs={24} sm={14}>
                            <Row gutter={16}>
                                <Col span={8}>
                                    <Statistic
                                        title="Answerable"
                                        value={answerableQuestions}
                                        suffix={`/ ${totalQuestions}`}
                                        valueStyle={{ color: '#52c41a', fontSize: 22 }}
                                    />
                                </Col>
                                <Col span={8}>
                                    <Statistic
                                        title="Gaps"
                                        value={unanswerable}
                                        valueStyle={{ color: '#ff4d4f', fontSize: 22 }}
                                    />
                                </Col>
                                <Col span={8}>
                                    <Statistic
                                        title="Categories"
                                        value={categories.length}
                                        valueStyle={{ fontSize: 22 }}
                                    />
                                </Col>
                            </Row>

                            {/* Improvement estimate */}
                            {hasMissingDocs && improvementDelta > 2 && (
                                <Alert
                                    type="info"
                                    showIcon
                                    style={{ marginTop: 16, fontSize: 12 }}
                                    message={
                                        <span>
                      Uploading missing documents could improve coverage to{' '}
                                            <strong>~{estimatedPercentAfter}%</strong>
                                            {' '}(+{Math.round(improvementDelta)}%)
                    </span>
                                    }
                                />
                            )}
                        </Col>
                    </Row>
                </Card>

                {/* ── Two-column layout: categories left, recommendations right ── */}
                <Row gutter={20}>

                    {/* Category breakdown */}
                    <Col xs={24} md={14}>
                        <Card
                            title={
                                <Space>
                                    <span>Coverage by Category</span>
                                    <Tag style={{ fontWeight: 'normal', fontSize: 11 }}>
                                        {categories.length} categories
                                    </Tag>
                                </Space>
                            }
                            bodyStyle={{ paddingTop: 4 }}
                        >
                            {categories.length === 0 ? (
                                <Text type="secondary">No category data available yet.</Text>
                            ) : (
                                categories.map((cat) => (
                                    <CategoryRow key={cat.category} cat={cat} />
                                ))
                            )}
                        </Card>
                    </Col>

                    {/* Recommendations panel */}
                    <Col xs={24} md={10}>

                        {/* Missing documents */}
                        {hasMissingDocs && (
                            <Card
                                title={
                                    <Space>
                                        <WarningOutlined style={{ color: '#faad14' }} />
                                        <span>Missing Documents</span>
                                    </Space>
                                }
                                style={{ marginBottom: 16 }}
                            >
                                <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 12 }}>
                                    Uploading these documents will significantly improve answer quality
                                    and reduce the number of questions requiring manual answers.
                                </Paragraph>
                                <List
                                    size="small"
                                    dataSource={missingDocSuggestions}
                                    renderItem={(doc) => (
                                        <List.Item style={{ padding: '6px 0', borderBottom: '1px solid #f5f5f5' }}>
                                            <Space size={8}>
                                                <FileTextOutlined style={{ color: '#8c8c8c' }} />
                                                <Text style={{ fontSize: 13 }}>{doc}</Text>
                                            </Space>
                                        </List.Item>
                                    )}
                                />
                                <Button
                                    type="dashed"
                                    icon={<UploadOutlined />}
                                    block
                                    style={{ marginTop: 16 }}
                                    onClick={() => navigate('/documents/upload')}
                                >
                                    Upload missing documents
                                </Button>
                                <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 8, textAlign: 'center' }}>
                                    After uploading, click "Re-check coverage" above.
                                </Text>
                            </Card>
                        )}

                        {/* Action card */}
                        <Card
                            title="What would you like to do?"
                            bodyStyle={{ display: 'flex', flexDirection: 'column', gap: 10 }}
                        >
                            {/* Primary action: generate */}
                            <Button
                                type="primary"
                                size="large"
                                block
                                icon={<ThunderboltOutlined />}
                                onClick={() => navigate(`/questionnaires/${id}`)}
                            >
                                {overallPercent >= 75
                                    ? `Generate answers (${overallPercent}% coverage)`
                                    : `Generate anyway (${overallPercent}% coverage)`}
                            </Button>

                            {/* Secondary: upload more docs */}
                            {hasMissingDocs && (
                                <Button
                                    size="large"
                                    block
                                    icon={<UploadOutlined />}
                                    onClick={() => navigate('/documents/upload')}
                                >
                                    Upload missing documents first
                                </Button>
                            )}

                            {/* Coverage quality hint */}
                            <div style={{
                                background: '#fafafa', borderRadius: 6,
                                padding: '10px 12px', marginTop: 4,
                            }}>
                                {overallPercent >= 90 && (
                                    <Text style={{ fontSize: 12, color: '#135200' }}>
                                        ✅ Excellent coverage. Most questions will be answered with high confidence.
                                    </Text>
                                )}
                                {overallPercent >= 75 && overallPercent < 90 && (
                                    <Text style={{ fontSize: 12, color: '#614700' }}>
                                        ⚠️ Good coverage. A few categories need stronger documentation.
                                        Consider uploading missing docs for best results.
                                    </Text>
                                )}
                                {overallPercent >= 55 && overallPercent < 75 && (
                                    <Text style={{ fontSize: 12, color: '#873800' }}>
                                        ⚠️ Fair coverage. {unanswerable} questions may receive "Insufficient evidence" answers.
                                        Uploading missing documents is strongly recommended.
                                    </Text>
                                )}
                                {overallPercent < 55 && (
                                    <Text style={{ fontSize: 12, color: '#820014' }}>
                                        ❌ Low coverage. Most questions in some categories cannot be answered.
                                        Upload your security policies before generating for best results.
                                    </Text>
                                )}
                            </div>
                        </Card>
                    </Col>
                </Row>
            </div>
        </AppLayout>
    );
}