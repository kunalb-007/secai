// src/pages/QuestionnaireDetailPage.jsx  — REPLACE ENTIRE FILE (Phase 5)
import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import {
    Table, Tag, Button, Typography, Alert, Spin,
    Card, Statistic, Row, Col, Select, Space,
    Tooltip, Badge, Popconfirm, Progress,
} from 'antd';
import {
    ArrowLeftOutlined, WarningOutlined,
    QuestionCircleOutlined, ReloadOutlined,
    DeleteOutlined, RobotOutlined,
    ThunderboltOutlined, EyeOutlined,
} from '@ant-design/icons';
import {
    getQuestionnaire, getQuestions,
    deleteQuestionnaire, startGeneration,
} from '../api/questionnaires';
import { useGenerationPoller } from '../hooks/useGenerationPoller';
import AppLayout from '../components/AppLayout';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';

dayjs.extend(relativeTime);

const { Title, Text } = Typography;
const { Option } = Select;

import { getCoverage }         from '../api/coverage';
import { CoverageBar, CoverageTierTag } from '../components/CoverageGauge';

const STATUS_COLOR = {
    PENDING:    'default',
    GENERATED:  'processing',
    APPROVED:   'success',
    EDITED:     'cyan',
    REJECTED:   'error',
};

const Q_STATUS_COLOR = {
    PARSED:     'blue',
    GENERATING: 'processing',
    COMPLETED:  'success',
    FAILED:     'error',
    UPLOADED:   'default',
};

export default function QuestionnaireDetailPage() {
    const { id } = useParams();
    const navigate = useNavigate();

    const [detail, setDetail]             = useState(null);
    const [questions, setQuestions]       = useState([]);
    const [totalQ, setTotalQ]             = useState(0);
    const [currentPage, setCurrentPage]   = useState(0);
    const [pageSize]                      = useState(50);
    const [statusFilter, setStatusFilter] = useState('');
    const [loadingDetail, setLoadingDetail] = useState(true);
    const [loadingQ, setLoadingQ]         = useState(false);
    const [pageError, setPageError]       = useState('');
    const [generating, setGenerating]     = useState(false);
    const [genError, setGenError]         = useState('');
    const [pollId, setPollId]             = useState(null);

    const [coverage, setCoverage] = useState(null);

    useEffect(() => {
        (async () => {
            try {
                const res = await getCoverage(id);
                if (res.data?.status === 'COMPLETE') setCoverage(res.data);
            } catch { /* non-fatal */ }
        })();
    }, [id]);

    // Poll when generation is running
    const onGenerationComplete = useCallback(() => {
        setPollId(null);
        setGenerating(false);
        fetchDetail();
    }, []); // eslint-disable-line

    const { job: liveJob } = useGenerationPoller(pollId, onGenerationComplete);

    const fetchDetail = useCallback(async () => {
        try {
            const res = await getQuestionnaire(id);
            setDetail(res.data);
            if (res.data?.aiJob?.status === 'RUNNING') {
                setGenerating(true);
                setPollId(id);
            }
        } catch {
            setPageError('Questionnaire not found or you do not have access.');
        } finally {
            setLoadingDetail(false);
        }
    }, [id]);

    const fetchQuestions = useCallback(async (page = 0, status = '') => {
        setLoadingQ(true);
        try {
            const res = await getQuestions(id, { status, page, size: pageSize });
            setQuestions(res.data.content);
            setTotalQ(res.data.totalElements);
        } catch {
            setPageError('Failed to load questions.');
        } finally {
            setLoadingQ(false);
        }
    }, [id, pageSize]);

    useEffect(() => { fetchDetail(); fetchQuestions(0, ''); }, [fetchDetail, fetchQuestions]);
    useEffect(() => { fetchQuestions(currentPage, statusFilter); }, [currentPage, statusFilter]); // eslint-disable-line

    const handleGenerate = async () => {
        setGenError('');
        setGenerating(true);
        try {
            await startGeneration(id);
            setPollId(id);
            fetchDetail();
        } catch (err) {
            setGenerating(false);
            setGenError(err.response?.data?.error || 'Failed to start generation.');
        }
    };

    const handleDelete = async () => {
        try {
            await deleteQuestionnaire(id);
            navigate('/questionnaires');
        } catch {
            setPageError('Failed to delete questionnaire.');
        }
    };

    const activeJob    = liveJob || detail?.aiJob;
    const isRunning    = generating || activeJob?.status === 'RUNNING';
    const isCompleted  = activeJob?.status === 'COMPLETED';
    const canGenerate  = !isRunning && (activeJob?.status === 'PENDING' || activeJob?.status === 'FAILED' || !activeJob);

    const statusCounts   = detail?.statusCounts || {};
    const pendingCount   = statusCounts.PENDING   || 0;
    const generatedCount = statusCounts.GENERATED || 0;
    const approvedCount  = statusCounts.APPROVED  || 0;
    const editedCount    = statusCounts.EDITED    || 0;
    const rejectedCount  = statusCounts.REJECTED  || 0;

    const columns = [
        {
            title: '#',
            dataIndex: 'questionNumber',
            width: 65,
            render: (v) => v
                ? <Text code style={{ fontSize: 12 }}>{v}</Text>
                : <Text type="secondary">—</Text>,
        },
        {
            title: 'Category',
            dataIndex: 'category',
            width: 150,
            ellipsis: true,
            render: (v) => v ? <Tag style={{ fontSize: 11 }}>{v}</Tag> : null,
        },
        {
            title: 'Question',
            dataIndex: 'questionText',
            render: (text) => <Text style={{ fontSize: 13 }}>{text}</Text>,
        },
        {
            title: 'Status',
            dataIndex: 'status',
            width: 105,
            render: (s) => (
                <Tag color={STATUS_COLOR[s] || 'default'} style={{ fontSize: 11 }}>{s}</Tag>
            ),
        },
    ];

    if (loadingDetail) {
        return (
            <AppLayout>
                <div style={{ textAlign: 'center', padding: 80 }}>
                    <Spin size="large" />
                </div>
            </AppLayout>
        );
    }

    if (pageError && !detail) {
        return (
            <AppLayout>
                <div style={{ padding: 24 }}>
                    <Alert type="error" message={pageError} showIcon />
                    <Button icon={<ArrowLeftOutlined />} style={{ marginTop: 16 }}
                            onClick={() => navigate('/questionnaires')}>
                        Back to Questionnaires
                    </Button>
                </div>
            </AppLayout>
        );
    }

    return (
        <AppLayout>
            <div style={{ padding: 24, maxWidth: 1100 }}>

                {/* Header */}
                <div style={{
                    display: 'flex', justifyContent: 'space-between',
                    alignItems: 'flex-start', marginBottom: 20,
                }}>
                    <div>
                        <Button type="text" icon={<ArrowLeftOutlined />}
                                onClick={() => navigate('/questionnaires')}
                                style={{ paddingLeft: 0, marginBottom: 4 }}>
                            All Questionnaires
                        </Button>
                        <Title level={4} style={{ margin: 0 }}>
                            {detail?.filename}
                            {detail?.originalFormat && (
                                <Tag
                                    color={{ XLSX: 'green', CSV: 'blue', DOCX: 'purple' }[detail.originalFormat]}
                                    style={{ marginLeft: 10, fontSize: 12 }}>
                                    {detail.originalFormat}
                                </Tag>
                            )}
                        </Title>
                        <Text type="secondary" style={{ fontSize: 13 }}>
                            Uploaded {dayjs(detail?.uploadedAt).fromNow()}
                        </Text>
                    </div>

                    <Space>
                        <Button icon={<ReloadOutlined />}
                                onClick={() => { fetchDetail(); fetchQuestions(currentPage, statusFilter); }}>
                            Refresh
                        </Button>

                        {/* Primary action: Generate if not yet done, Review if done */}
                        {(isCompleted || (generatedCount > 0)) ? (
                            <Button
                                type="primary"
                                icon={<EyeOutlined />}
                                onClick={() => navigate(`/questionnaires/${id}/review`)}
                            >
                                Review Answers
                            </Button>
                        ) : (
                            <Button
                                type="primary"
                                icon={<ThunderboltOutlined />}
                                onClick={handleGenerate}
                                loading={isRunning}
                                disabled={!canGenerate}
                            >
                                {isRunning ? 'Generating…' : 'Generate Answers'}
                            </Button>
                        )}

                        <Popconfirm
                            title="Delete this questionnaire?"
                            description="All questions will be permanently deleted."
                            onConfirm={handleDelete}
                            okText="Delete"
                            okType="danger"
                            placement="bottomRight"
                        >
                            <Button danger icon={<DeleteOutlined />}>Delete</Button>
                        </Popconfirm>
                    </Space>
                </div>

                {/* Errors */}
                {pageError && (
                    <Alert type="error" message={pageError} showIcon closable
                           style={{ marginBottom: 16 }} onClose={() => setPageError('')} />
                )}
                {genError && (
                    <Alert type="error" message={genError} showIcon closable
                           style={{ marginBottom: 16 }} onClose={() => setGenError('')} />
                )}

                {/* Low confidence warning */}
                {detail?.lowConfidenceFlag && (
                    <Alert type="warning" showIcon icon={<WarningOutlined />}
                           message="Low parse confidence"
                           description={detail.warningMessage}
                           style={{ marginBottom: 16 }} />
                )}

                {/* Generation progress */}
                {isRunning && activeJob && (
                    <Card style={{ marginBottom: 16, borderColor: '#1890ff' }}
                          bodyStyle={{ padding: '14px 20px' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
                            <RobotOutlined style={{ fontSize: 18, color: '#1890ff' }} />
                            <Text strong>{activeJob.statusMessage || 'Generating answers…'}</Text>
                        </div>
                        <Progress
                            percent={activeJob.progressPercent || 0}
                            status="active"
                            strokeColor={{ from: '#108ee9', to: '#87d068' }}
                            format={() => `${activeJob.completedQuestions} / ${activeJob.totalQuestions}`}
                        />
                    </Card>
                )}

                {/* Stats */}
                <Row gutter={12} style={{ marginBottom: 20 }}>
                    {[
                        { title: 'Total',      value: detail?.totalQuestions || 0, color: undefined },
                        { title: 'Pending',    value: pendingCount,   color: '#8c8c8c' },
                        { title: 'Generated',  value: generatedCount, color: '#1890ff' },
                        { title: 'Approved',   value: approvedCount,  color: '#52c41a' },
                        { title: 'Edited',     value: editedCount,    color: '#13c2c2' },
                        { title: 'Rejected',   value: rejectedCount,  color: '#ff4d4f' },
                    ].map(({ title, value, color }) => (
                        <Col span={4} key={title}>
                            <Card size="small">
                                <Statistic title={title} value={value}
                                           valueStyle={{ fontSize: 20, color }} />
                            </Card>
                        </Col>
                    ))}
                </Row>

                {/* Coverage banner — shown when analysis is complete */}
                {coverage && (
                    <Card
                        style={{ marginBottom: 16, cursor: 'pointer' }}
                        bodyStyle={{ padding: '14px 20px' }}
                        onClick={() => navigate(`/questionnaires/${id}/coverage`)}
                        hoverable
                    >
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                            <div style={{ flex: 1, marginRight: 24 }}>
                                <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
                                    <Text strong style={{ fontSize: 14 }}>Knowledge Base Coverage</Text>
                                    <CoverageTierTag tier={coverage.coverageTier} />
                                    {coverage.missingDocSuggestions?.length > 0 && (
                                        <Tag color="warning" icon={<WarningOutlined />}>
                                            {coverage.missingDocSuggestions.length} doc{coverage.missingDocSuggestions.length > 1 ? 's' : ''} missing
                                        </Tag>
                                    )}
                                </div>
                                <CoverageBar percent={coverage.overallPercent} tier={coverage.coverageTier} showLabel={false} />
                            </div>
                            <div style={{ textAlign: 'right', minWidth: 80 }}>
                                <div style={{ fontSize: 28, fontWeight: 800,
                                    color: coverage.overallPercent >= 75 ? '#52c41a'
                                        : coverage.overallPercent >= 55 ? '#faad14' : '#ff4d4f' }}>
                                    {coverage.overallPercent}%
                                </div>
                                <Text type="secondary" style={{ fontSize: 11 }}>View details →</Text>
                            </div>
                        </div>
                    </Card>
                )}

                {/* Questions table */}
                <Card
                    title={
                        <Space>
                            <QuestionCircleOutlined />
                            <span>Questions</span>
                            <Badge count={totalQ} showZero
                                   style={{ backgroundColor: '#1890ff' }} overflowCount={9999} />
                        </Space>
                    }
                    extra={
                        <Select
                            value={statusFilter || 'ALL'}
                            style={{ width: 140 }} size="small"
                            onChange={(val) => {
                                setStatusFilter(val === 'ALL' ? '' : val);
                                setCurrentPage(0);
                            }}
                        >
                            <Option value="ALL">All statuses</Option>
                            <Option value="PENDING">Pending</Option>
                            <Option value="GENERATED">Generated</Option>
                            <Option value="APPROVED">Approved</Option>
                            <Option value="EDITED">Edited</Option>
                            <Option value="REJECTED">Rejected</Option>
                        </Select>
                    }
                >
                    <Table
                        dataSource={questions}
                        columns={columns}
                        rowKey="id"
                        loading={loadingQ}
                        size="small"
                        pagination={{
                            current:         currentPage + 1,
                            pageSize:        pageSize,
                            total:           totalQ,
                            showSizeChanger: false,
                            showTotal:       (total) => `${total} questions`,
                            onChange:        (antPage) => setCurrentPage(antPage - 1),
                        }}
                    />
                </Card>
            </div>
        </AppLayout>
    );
}