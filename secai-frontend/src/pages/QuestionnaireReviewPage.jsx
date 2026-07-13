// src/pages/QuestionnaireReviewPage.jsx  — NEW FILE (Phase 5)
import { useEffect, useState, useCallback, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
    Table, Tag, Button, Typography, Alert, Spin,
    Card, Progress, Modal, Input, Space, Tooltip,
    Badge, Select, Row, Col, Statistic, message,
    Popconfirm,
} from 'antd';
import {
    ArrowLeftOutlined, RobotOutlined, CheckOutlined,
    CloseOutlined, EditOutlined, ReloadOutlined,
    CheckCircleOutlined, WarningOutlined,
    ExclamationCircleOutlined, ThunderboltOutlined,
    DownloadOutlined,
} from '@ant-design/icons';
import AppLayout from '../components/AppLayout';
import {
    getQuestionnaire, getQuestions, startGeneration,
    editQuestion, approveQuestion, rejectQuestion,
} from '../api/questionnaires';
import { useGenerationPoller } from '../hooks/useGenerationPoller';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';

dayjs.extend(relativeTime);

const { Title, Text, Paragraph } = Typography;
const { TextArea } = Input;
const { Option } = Select;

// ── Confidence score → colour mapping ─────────────────────────────────────────
// Green: ≥ 0.80 (high confidence)
// Yellow: 0.50–0.79 (medium)
// Red: < 0.50 (low — needs manual review)
function scoreToColor(score) {
    if (score == null) return '#d9d9d9';
    if (score >= 0.80) return '#52c41a';
    if (score >= 0.50) return '#faad14';
    return '#ff4d4f';
}

function scoreToRowClass(score) {
    if (score == null || score >= 0.80) return '';
    if (score >= 0.50) return 'row-yellow';
    return 'row-red';
}

const STATUS_TAG = {
    PENDING:   { color: 'default',    label: 'Pending'   },
    GENERATED: { color: 'processing', label: 'AI Answer' },
    APPROVED:  { color: 'success',    label: 'Approved'  },
    EDITED:    { color: 'cyan',       label: 'Edited'    },
    REJECTED:  { color: 'error',      label: 'Rejected'  },
};

// ── Edit Modal ─────────────────────────────────────────────────────────────────
function EditModal({ question, visible, onSave, onCancel, saving }) {
    const [value, setValue] = useState('');

    useEffect(() => {
        if (visible && question) {
            // Pre-fill with manual_answer if one exists, else ai_answer
            setValue(question.manualAnswer || question.aiAnswer || '');
        }
    }, [visible, question]);

    return (
        <Modal
            title={
                <Space>
                    <EditOutlined />
                    Edit Answer
                </Space>
            }
            open={visible}
            onCancel={onCancel}
            onOk={() => onSave(value)}
            okText="Save Answer"
            cancelText="Cancel"
            confirmLoading={saving}
            width={680}
            destroyOnClose
        >
            {question && (
                <>
                    {/* Question context */}
                    <div style={{
                        background: '#f5f5f5',
                        borderRadius: 6,
                        padding: '10px 14px',
                        marginBottom: 16,
                    }}>
                        <Text type="secondary" style={{ fontSize: 11, fontWeight: 600, letterSpacing: '0.05em' }}>
                            {question.questionNumber && `${question.questionNumber} · `}
                            {question.category || 'Question'}
                        </Text>
                        <Paragraph style={{ margin: '4px 0 0', fontWeight: 500 }}>
                            {question.questionText}
                        </Paragraph>
                    </div>

                    {/* AI answer for reference */}
                    {question.aiAnswer && (
                        <div style={{ marginBottom: 14 }}>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                                AI answer (for reference):
                            </Text>
                            <div style={{
                                padding: '8px 12px',
                                background: '#e6f4ff',
                                borderRadius: 4,
                                marginTop: 4,
                                fontSize: 13,
                                color: '#1677ff',
                            }}>
                                {question.aiAnswer}
                            </div>
                            {question.evidence && question.evidence !== 'N/A' && (
                                <Text type="secondary" style={{ fontSize: 11, marginTop: 4, display: 'block' }}>
                                    Evidence: {question.evidence}
                                </Text>
                            )}
                        </div>
                    )}

                    {/* Editable answer */}
                    <div>
                        <Text strong style={{ fontSize: 13 }}>Your answer:</Text>
                        <TextArea
                            value={value}
                            onChange={(e) => setValue(e.target.value)}
                            rows={5}
                            placeholder="Type your answer here…"
                            style={{ marginTop: 6 }}
                            autoFocus
                        />
                    </div>
                </>
            )}
        </Modal>
    );
}

// ── Main component ─────────────────────────────────────────────────────────────
export default function QuestionnaireReviewPage() {
    const { id } = useParams();
    const navigate = useNavigate();

    // ── Data state ───────────────────────────────────────────────────────────────
    const [detail, setDetail]           = useState(null);
    const [questions, setQuestions]     = useState([]);
    const [totalQ, setTotalQ]           = useState(0);
    const [currentPage, setCurrentPage] = useState(0);
    const [pageSize]                    = useState(50);
    const [statusFilter, setStatusFilter] = useState('');
    const [loadingDetail, setLoadingDetail] = useState(true);
    const [loadingQ, setLoadingQ]       = useState(false);
    const [pageError, setPageError]     = useState('');

    // ── Generation state ─────────────────────────────────────────────────────────
    const [generating, setGenerating]   = useState(false);
    const [genError, setGenError]       = useState('');

    // ── Review/edit state ────────────────────────────────────────────────────────
    const [editingQuestion, setEditingQuestion] = useState(null);
    const [editModalOpen, setEditModalOpen]     = useState(false);
    const [savingEdit, setSavingEdit]           = useState(false);
    const [actionLoading, setActionLoading]     = useState({}); // { [questionId]: true }

    // ── Generation polling ────────────────────────────────────────────────────────
    // Only poll while we know generation is running (set by startGeneration or
    // when initial load shows RUNNING status).
    const [pollId, setPollId] = useState(null);

    const onGenerationComplete = useCallback((finalJob) => {
        setPollId(null);
        setGenerating(false);
        if (finalJob.status === 'FAILED') {
            setGenError('Generation failed. Some questions may not have answers. Please try again.');
        }
        // Refresh questions to show AI answers
        fetchQuestions(0, '');
        fetchDetail();
    }, []); // eslint-disable-line

    const { job: liveJob } = useGenerationPoller(pollId, onGenerationComplete);

    // ── Fetchers ──────────────────────────────────────────────────────────────────
    const fetchDetail = useCallback(async () => {
        try {
            const res = await getQuestionnaire(id);
            setDetail(res.data);

            // If generation is already running when we load the page, start polling
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

    useEffect(() => {
        fetchDetail();
        fetchQuestions(0, '');
    }, [fetchDetail, fetchQuestions]);

    useEffect(() => {
        fetchQuestions(currentPage, statusFilter);
    }, [currentPage, statusFilter]); // eslint-disable-line

    // ── Generation trigger ────────────────────────────────────────────────────────
    const handleGenerate = async () => {
        setGenError('');
        setGenerating(true);
        try {
            await startGeneration(id);
            setPollId(id); // start polling
            fetchDetail();
        } catch (err) {
            setGenerating(false);
            setGenError(err.response?.data?.error || 'Failed to start generation. Please try again.');
        }
    };

    // ── Review actions ────────────────────────────────────────────────────────────
    const setActionState = (qId, loading) =>
        setActionLoading((prev) => ({ ...prev, [qId]: loading }));

    const handleApprove = async (question) => {
        setActionState(question.id, true);
        try {
            const res = await approveQuestion(question.id);
            updateQuestionInList(res.data);
            message.success('Answer approved');
        } catch {
            message.error('Failed to approve. Please try again.');
        } finally {
            setActionState(question.id, false);
        }
    };

    const handleReject = async (question) => {
        setActionState(question.id, true);
        try {
            const res = await rejectQuestion(question.id);
            updateQuestionInList(res.data);
            message.warning('Answer rejected');
        } catch {
            message.error('Failed to reject. Please try again.');
        } finally {
            setActionState(question.id, false);
        }
    };

    const handleOpenEdit = (question) => {
        setEditingQuestion(question);
        setEditModalOpen(true);
    };

    const handleSaveEdit = async (newAnswer) => {
        if (!editingQuestion) return;
        setSavingEdit(true);
        try {
            const res = await editQuestion(editingQuestion.id, newAnswer);
            updateQuestionInList(res.data);
            setEditModalOpen(false);
            setEditingQuestion(null);
            message.success('Answer saved');
        } catch {
            message.error('Failed to save. Please try again.');
        } finally {
            setSavingEdit(false);
        }
    };

    // Update a single question in the local list without re-fetching the page
    const updateQuestionInList = (updatedQ) => {
        setQuestions((prev) =>
            prev.map((q) => q.id === updatedQ.id ? updatedQ : q)
        );
        // Refresh detail so status counts are accurate
        fetchDetail();
    };

    // ── Stats from detail ─────────────────────────────────────────────────────────
    const statusCounts   = detail?.statusCounts   || {};
    const pendingCount   = statusCounts.PENDING    || 0;
    const generatedCount = statusCounts.GENERATED  || 0;
    const approvedCount  = statusCounts.APPROVED   || 0;
    const editedCount    = statusCounts.EDITED     || 0;
    const rejectedCount  = statusCounts.REJECTED   || 0;
    const totalCount     = detail?.totalQuestions  || 0;
    const reviewedCount  = approvedCount + editedCount;
    const reviewProgress = totalCount ? Math.round((reviewedCount / totalCount) * 100) : 0;

    // ── Generation progress info ──────────────────────────────────────────────────
    const activeJob   = liveJob || detail?.aiJob;
    const isRunning   = generating || activeJob?.status === 'RUNNING';
    const genProgress = activeJob?.progressPercent ?? 0;
    const genMessage  = activeJob?.statusMessage   ?? '';

    // ── Compute whether generation can be triggered ───────────────────────────────
    const canGenerate = !isRunning
        && activeJob?.status !== 'RUNNING'
        && (activeJob?.status === 'PENDING' || activeJob?.status === 'FAILED' || !activeJob);

    // ── Table columns ─────────────────────────────────────────────────────────────
    const columns = [
        {
            title: '#',
            dataIndex: 'questionNumber',
            width: 60,
            render: (v) => v
                ? <Text code style={{ fontSize: 11 }}>{v}</Text>
                : <Text type="secondary">—</Text>,
        },
        {
            title: 'Category',
            dataIndex: 'category',
            width: 140,
            ellipsis: true,
            render: (v) => v ? <Tag style={{ fontSize: 11 }}>{v}</Tag> : null,
        },
        {
            title: 'Question',
            dataIndex: 'questionText',
            width: '26%',
            render: (text) => (
                <Text style={{ fontSize: 13 }}>{text}</Text>
            ),
        },
        {
            title: 'AI Answer',
            dataIndex: 'aiAnswer',
            render: (answer, record) => {
                if (!answer) {
                    return <Text type="secondary" style={{ fontStyle: 'italic' }}>Not yet generated</Text>;
                }
                // Show manual_answer with a flag if EDITED
                const displayAnswer = record.status === 'EDITED' && record.manualAnswer
                    ? record.manualAnswer
                    : answer;
                return (
                    <div>
                        <Text style={{ fontSize: 13 }}>{displayAnswer}</Text>
                        {record.evidence && record.evidence !== 'N/A' && (
                            <div style={{ marginTop: 4 }}>
                                <Text type="secondary" style={{ fontSize: 11 }}>
                                    📄 {record.evidence}
                                </Text>
                            </div>
                        )}
                        {record.status === 'EDITED' && record.manualAnswer && (
                            <Tag color="cyan" style={{ marginTop: 4, fontSize: 10 }}>Edited</Tag>
                        )}
                    </div>
                );
            },
        },
        {
            title: 'Score',
            dataIndex: 'retrievalScore',
            width: 80,
            align: 'center',
            render: (score) => {
                if (score == null) return <Text type="secondary">—</Text>;
                return (
                    <Tooltip title={`Retrieval confidence: ${(score * 100).toFixed(0)}%`}>
                        <div style={{
                            display: 'inline-block',
                            padding: '2px 8px',
                            borderRadius: 10,
                            background: scoreToColor(score),
                            color: '#fff',
                            fontWeight: 700,
                            fontSize: 12,
                            cursor: 'default',
                        }}>
                            {(score * 100).toFixed(0)}%
                        </div>
                    </Tooltip>
                );
            },
        },
        {
            title: 'Status',
            dataIndex: 'status',
            width: 100,
            render: (s) => {
                const cfg = STATUS_TAG[s] || { color: 'default', label: s };
                return <Tag color={cfg.color}>{cfg.label}</Tag>;
            },
        },
        {
            title: 'Actions',
            width: 130,
            render: (_, record) => {
                if (!record.aiAnswer) return null;
                const loading = actionLoading[record.id];

                return (
                    <Space size={4}>
                        <Tooltip title="Edit answer">
                            <Button
                                type="text"
                                icon={<EditOutlined />}
                                size="small"
                                onClick={() => handleOpenEdit(record)}
                                disabled={loading}
                            />
                        </Tooltip>
                        <Tooltip title="Approve">
                            <Button
                                type="text"
                                icon={<CheckOutlined style={{ color: '#52c41a' }} />}
                                size="small"
                                onClick={() => handleApprove(record)}
                                loading={loading}
                                disabled={record.status === 'APPROVED'}
                            />
                        </Tooltip>
                        <Tooltip title="Reject">
                            <Button
                                type="text"
                                danger
                                icon={<CloseOutlined />}
                                size="small"
                                onClick={() => handleReject(record)}
                                loading={loading}
                                disabled={record.status === 'REJECTED'}
                            />
                        </Tooltip>
                    </Space>
                );
            },
        },
    ];

    // ── Loading state ─────────────────────────────────────────────────────────────
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
                    <Button
                        icon={<ArrowLeftOutlined />}
                        style={{ marginTop: 16 }}
                        onClick={() => navigate('/questionnaires')}
                    >
                        Back to Questionnaires
                    </Button>
                </div>
            </AppLayout>
        );
    }

    // ── Render ────────────────────────────────────────────────────────────────────
    return (
        <AppLayout>
            <div style={{ padding: 24, maxWidth: 1200 }}>

                {/* ── Page header ──────────────────────────────────────────────── */}
                <div style={{
                    display: 'flex', justifyContent: 'space-between',
                    alignItems: 'flex-start', marginBottom: 20,
                }}>
                    <div>
                        <Button
                            type="text"
                            icon={<ArrowLeftOutlined />}
                            onClick={() => navigate('/questionnaires')}
                            style={{ paddingLeft: 0, marginBottom: 4 }}
                        >
                            All Questionnaires
                        </Button>
                        <Title level={4} style={{ margin: 0 }}>
                            {detail?.filename}
                        </Title>
                        <Text type="secondary" style={{ fontSize: 13 }}>
                            {totalCount} questions · Uploaded {dayjs(detail?.uploadedAt).fromNow()}
                        </Text>
                    </div>

                    <Space>
                        <Button
                            icon={<ReloadOutlined />}
                            onClick={() => { fetchDetail(); fetchQuestions(currentPage, statusFilter); }}
                        >
                            Refresh
                        </Button>

                        {/* Generate answers button */}
                        <Button
                            type="primary"
                            icon={<ThunderboltOutlined />}
                            onClick={handleGenerate}
                            loading={isRunning}
                            disabled={!canGenerate}
                        >
                            {isRunning ? 'Generating…' : 'Generate Answers'}
                        </Button>

                        {/* Export button — placeholder for Phase 7 */}
                        <Tooltip title="Export coming in Phase 7">
                            <Button icon={<DownloadOutlined />} disabled>
                                Export Excel
                            </Button>
                        </Tooltip>
                    </Space>
                </div>

                {/* ── Error alerts ─────────────────────────────────────────────── */}
                {pageError && (
                    <Alert type="error" message={pageError} showIcon closable
                           style={{ marginBottom: 16 }} onClose={() => setPageError('')} />
                )}
                {genError && (
                    <Alert type="error" message={genError} showIcon closable
                           style={{ marginBottom: 16 }} onClose={() => setGenError('')} />
                )}

                {/* ── Generation progress bar ──────────────────────────────────── */}
                {(isRunning || activeJob?.status === 'COMPLETED') && (
                    <Card
                        style={{ marginBottom: 20, borderColor: isRunning ? '#1890ff' : '#52c41a' }}
                        bodyStyle={{ padding: '16px 20px' }}
                    >
                        <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 12 }}>
                            <RobotOutlined style={{
                                fontSize: 20,
                                color: isRunning ? '#1890ff' : '#52c41a',
                            }} />
                            <Text strong>
                                {isRunning
                                    ? genMessage || 'AI is generating answers…'
                                    : activeJob?.statusMessage || 'Generation complete'}
                            </Text>
                        </div>
                        <Progress
                            percent={isRunning ? genProgress : 100}
                            status={isRunning ? 'active' : 'success'}
                            strokeColor={isRunning
                                ? { from: '#108ee9', to: '#87d068' }
                                : '#52c41a'
                            }
                            format={(pct) =>
                                isRunning
                                    ? `${activeJob?.completedQuestions ?? 0} / ${activeJob?.totalQuestions ?? totalCount}`
                                    : '✓ Complete'
                            }
                        />
                        {isRunning && (
                            <Text type="secondary" style={{ fontSize: 12, marginTop: 6, display: 'block' }}>
                                Answers appear automatically as they're generated. This page updates live.
                            </Text>
                        )}
                    </Card>
                )}

                {/* ── Stats strip ──────────────────────────────────────────────── */}
                <Row gutter={12} style={{ marginBottom: 20 }}>
                    <Col span={4}>
                        <Card size="small">
                            <Statistic title="Total" value={totalCount}
                                       valueStyle={{ fontSize: 20 }} />
                        </Card>
                    </Col>
                    <Col span={4}>
                        <Card size="small">
                            <Statistic title="Pending" value={pendingCount}
                                       valueStyle={{ fontSize: 20, color: '#8c8c8c' }} />
                        </Card>
                    </Col>
                    <Col span={4}>
                        <Card size="small">
                            <Statistic title="AI Generated" value={generatedCount}
                                       valueStyle={{ fontSize: 20, color: '#1890ff' }} />
                        </Card>
                    </Col>
                    <Col span={4}>
                        <Card size="small">
                            <Statistic title="Approved" value={approvedCount}
                                       valueStyle={{ fontSize: 20, color: '#52c41a' }} />
                        </Card>
                    </Col>
                    <Col span={4}>
                        <Card size="small">
                            <Statistic title="Edited" value={editedCount}
                                       valueStyle={{ fontSize: 20, color: '#13c2c2' }} />
                        </Card>
                    </Col>
                    <Col span={4}>
                        <Card size="small">
                            <Statistic title="Rejected" value={rejectedCount}
                                       valueStyle={{ fontSize: 20, color: '#ff4d4f' }} />
                        </Card>
                    </Col>
                </Row>

                {/* ── Review progress ───────────────────────────────────────────── */}
                {reviewedCount > 0 && (
                    <div style={{ marginBottom: 16 }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                            <Text type="secondary" style={{ fontSize: 12 }}>Review progress</Text>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                                {reviewedCount} / {totalCount} reviewed ({reviewProgress}%)
                            </Text>
                        </div>
                        <Progress
                            percent={reviewProgress}
                            showInfo={false}
                            strokeColor="#52c41a"
                            size="small"
                        />
                    </div>
                )}

                {/* ── Questions table ───────────────────────────────────────────── */}
                <Card
                    title={
                        <Space>
                            <span>Questions</span>
                            <Badge
                                count={totalQ}
                                showZero
                                style={{ backgroundColor: '#1890ff' }}
                                overflowCount={9999}
                            />
                        </Space>
                    }
                    extra={
                        <Select
                            value={statusFilter || 'ALL'}
                            style={{ width: 150 }}
                            size="small"
                            onChange={(val) => {
                                setStatusFilter(val === 'ALL' ? '' : val);
                                setCurrentPage(0);
                            }}
                        >
                            <Option value="ALL">All statuses</Option>
                            <Option value="PENDING">Pending</Option>
                            <Option value="GENERATED">AI Generated</Option>
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
                        rowClassName={(record) => scoreToRowClass(record.retrievalScore)}
                        pagination={{
                            current:         currentPage + 1,
                            pageSize:        pageSize,
                            total:           totalQ,
                            showSizeChanger: false,
                            showTotal:       (total, range) =>
                                `${range[0]}–${range[1]} of ${total} questions`,
                            onChange: (antPage) => setCurrentPage(antPage - 1),
                        }}
                        locale={{
                            emptyText: (
                                <div style={{ padding: 40, textAlign: 'center' }}>
                                    <RobotOutlined style={{ fontSize: 36, color: '#d9d9d9', marginBottom: 12 }} />
                                    <p style={{ color: '#8c8c8c', marginBottom: statusFilter ? 0 : 16 }}>
                                        {statusFilter
                                            ? `No questions with status "${statusFilter}"`
                                            : pendingCount > 0
                                                ? 'Click "Generate Answers" to have AI answer all questions.'
                                                : 'No questions found'}
                                    </p>
                                    {!statusFilter && pendingCount > 0 && canGenerate && (
                                        <Button
                                            type="primary"
                                            icon={<ThunderboltOutlined />}
                                            onClick={handleGenerate}
                                        >
                                            Generate Answers
                                        </Button>
                                    )}
                                </div>
                            ),
                        }}
                    />
                </Card>

                {/* ── Score legend ──────────────────────────────────────────────── */}
                <div style={{
                    display: 'flex', gap: 20, marginTop: 12,
                    padding: '8px 16px', background: '#fafafa',
                    borderRadius: 6, border: '1px solid #f0f0f0',
                }}>
                    <Text type="secondary" style={{ fontSize: 12 }}>Confidence:</Text>
                    {[
                        { color: '#52c41a', label: '≥ 80% — High' },
                        { color: '#faad14', label: '50–79% — Review' },
                        { color: '#ff4d4f', label: '< 50% — Low' },
                    ].map(({ color, label }) => (
                        <Space key={label} size={4}>
                            <div style={{
                                width: 10, height: 10, borderRadius: '50%',
                                background: color, display: 'inline-block',
                            }} />
                            <Text type="secondary" style={{ fontSize: 12 }}>{label}</Text>
                        </Space>
                    ))}
                </div>

                {/* ── Edit Modal ────────────────────────────────────────────────── */}
                <EditModal
                    question={editingQuestion}
                    visible={editModalOpen}
                    onSave={handleSaveEdit}
                    onCancel={() => { setEditModalOpen(false); setEditingQuestion(null); }}
                    saving={savingEdit}
                />
            </div>

            {/* ── Row color styles (injected inline for simplicity) ────────────── */}
            <style>{`
        .row-yellow td { background-color: #fffbe6 !important; }
        .row-yellow:hover td { background-color: #fff1b8 !important; }
        .row-red td { background-color: #fff2f0 !important; }
        .row-red:hover td { background-color: #ffccc7 !important; }
      `}</style>
        </AppLayout>
    );
}