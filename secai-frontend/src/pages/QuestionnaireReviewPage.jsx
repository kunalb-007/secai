// src/pages/QuestionnaireReviewPage.jsx
import {
    useEffect, useState, useCallback, useMemo, useRef,
} from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
    Table, Tag, Button, Typography, Alert, Spin, Card,
    Progress, Modal, Input, Space, Tooltip, Badge, Select,
    Row, Col, Statistic, message, Checkbox, Popconfirm, Dropdown,
} from 'antd';
import {
    ArrowLeftOutlined, RobotOutlined, CheckOutlined, CloseOutlined,
    EditOutlined, ReloadOutlined, CheckCircleOutlined,
    ThunderboltOutlined, DownloadOutlined, FilterOutlined,
    WarningOutlined, ExclamationCircleOutlined, DownOutlined,
} from '@ant-design/icons';
import AppLayout from '../components/AppLayout';
import {
    getQuestionnaire, getQuestions, startGeneration,
    editQuestion, approveQuestion, rejectQuestion,
    bulkApprove, exportQuestionnaire,
} from '../api/questionnaires';
import { useGenerationPoller } from '../hooks/useGenerationPoller';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';

dayjs.extend(relativeTime);

const { Text, Paragraph } = Typography;
const { TextArea } = Input;
const { Option }  = Select;

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────

const SCORE_BAND = (score) => {
    if (score == null) return null;
    if (score >= 0.80) return 'high';
    if (score >= 0.50) return 'medium';
    return 'low';
};

const BAND_COLORS = {
    high:   { bg: '#f6ffed', border: '#b7eb8f', badge: '#52c41a', text: '#135200' },
    medium: { bg: '#fffbe6', border: '#ffe58f', badge: '#faad14', text: '#614700' },
    low:    { bg: '#fff2f0', border: '#ffccc7', badge: '#ff4d4f', text: '#820014' },
};

const STATUS_CFG = {
    PENDING:   { color: 'default',    label: 'Pending'    },
    GENERATED: { color: 'processing', label: 'AI Answer'  },
    APPROVED:  { color: 'success',    label: 'Approved'   },
    EDITED:    { color: 'cyan',       label: 'Edited'     },
    REJECTED:  { color: 'error',      label: 'Rejected'   },
};

// Filter options shown in the dropdown
const FILTER_OPTIONS = [
    { value: '',               label: 'All questions'    },
    { value: 'GENERATED',     label: 'AI answers (unreviewed)' },
    { value: 'low_confidence', label: '⚠ Low confidence (< 50%)' },
    { value: 'PENDING',        label: 'Pending (no answer yet)' },
    { value: 'APPROVED',       label: 'Approved'         },
    { value: 'EDITED',         label: 'Edited'           },
    { value: 'REJECTED',       label: 'Rejected'         },
];

// ─────────────────────────────────────────────────────────────────────────────
// Edit Modal
// ─────────────────────────────────────────────────────────────────────────────

function EditModal({ question, open, onSave, onCancel, saving }) {
    const [value, setValue] = useState('');

    useEffect(() => {
        if (open && question) {
            setValue(question.manualAnswer || question.aiAnswer || '');
        }
    }, [open, question]);

    return (
        <Modal
            title={<Space><EditOutlined /> Edit Answer</Space>}
            open={open}
            onCancel={onCancel}
            onOk={() => onSave(value)}
            okText="Save Answer"
            cancelText="Cancel"
            confirmLoading={saving}
            width={700}
            destroyOnClose
        >
            {question && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>

                    {/* Question context banner */}
                    <div style={{
                        background: '#f5f5f5', borderRadius: 6,
                        padding: '10px 14px', borderLeft: '3px solid #1890ff',
                    }}>
                        <Text type="secondary" style={{ fontSize: 11, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                            {[question.questionNumber, question.category].filter(Boolean).join(' · ') || 'Question'}
                        </Text>
                        <Paragraph style={{ margin: '6px 0 0', fontWeight: 500, fontSize: 14 }}>
                            {question.questionText}
                        </Paragraph>
                    </div>

                    {/* AI answer for reference */}
                    {question.aiAnswer && (
                        <div>
                            <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>
                                AI answer <span style={{ color: '#8c8c8c' }}>(for reference — not saved unless you keep it)</span>
                            </Text>
                            <div style={{
                                padding: '8px 12px', background: '#e6f4ff',
                                borderRadius: 4, fontSize: 13, color: '#0958d9',
                                lineHeight: 1.6,
                            }}>
                                {question.aiAnswer}
                            </div>
                            {question.evidence && question.evidence !== 'N/A' && (
                                <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 4 }}>
                                    📄 Source: {question.evidence}
                                </Text>
                            )}
                        </div>
                    )}

                    {/* Editable textarea */}
                    <div>
                        <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 6 }}>
                            Your answer:
                        </Text>
                        <TextArea
                            value={value}
                            onChange={(e) => setValue(e.target.value)}
                            rows={5}
                            placeholder="Type your answer here…"
                            autoFocus
                            style={{ fontSize: 13 }}
                        />
                        <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 4 }}>
                            This will be saved as the final answer in the export.
                        </Text>
                    </div>
                </div>
            )}
        </Modal>
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Score badge
// ─────────────────────────────────────────────────────────────────────────────

function ScoreBadge({ score }) {
    if (score == null) return <Text type="secondary" style={{ fontSize: 12 }}>—</Text>;
    const band   = SCORE_BAND(score);
    const colors = BAND_COLORS[band];
    const pct    = Math.round(score * 100);
    return (
        <Tooltip title={
            band === 'high'   ? 'High confidence — answer is likely correct' :
                band === 'medium' ? 'Medium confidence — review recommended' :
                    'Low confidence — manual review required'
        }>
      <span style={{
          display: 'inline-block',
          padding: '2px 8px',
          borderRadius: 10,
          background: colors.badge,
          color: '#fff',
          fontWeight: 700,
          fontSize: 12,
          cursor: 'default',
          minWidth: 40,
          textAlign: 'center',
      }}>
        {pct}%
      </span>
        </Tooltip>
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Main Review Page
// ─────────────────────────────────────────────────────────────────────────────

export default function QuestionnaireReviewPage() {
    const { id }   = useParams();
    const navigate = useNavigate();

    // ── Data ──────────────────────────────────────────────────────────────────
    const [detail, setDetail]               = useState(null);
    const [questions, setQuestions]         = useState([]);
    const [totalQ, setTotalQ]               = useState(0);
    const [currentPage, setCurrentPage]     = useState(0);
    const PAGE_SIZE                         = 50;
    const [activeFilter, setActiveFilter]   = useState('');   // '' = all
    const [loadingDetail, setLoadingDetail] = useState(true);
    const [loadingQ, setLoadingQ]           = useState(false);
    const [pageError, setPageError]         = useState('');

    // ── Generation ────────────────────────────────────────────────────────────
    const [generating, setGenerating]  = useState(false);
    const [genError, setGenError]      = useState('');
    const [pollId, setPollId]          = useState(null);

    // ── Selection (bulk actions) ──────────────────────────────────────────────
    const [selectedIds, setSelectedIds] = useState([]);

    // ── Edit modal ────────────────────────────────────────────────────────────
    const [editTarget, setEditTarget]   = useState(null);
    const [editOpen, setEditOpen]       = useState(false);
    const [savingEdit, setSavingEdit]   = useState(false);

    // ── Per-row action loading ─────────────────────────────────────────────────
    const [rowLoading, setRowLoading]   = useState({});

    // ── Export ────────────────────────────────────────────────────────────────
    const [exporting, setExporting]     = useState(false);

    // ─────────────────────────────────────────────────────────────────────────
    // Generation polling
    // ─────────────────────────────────────────────────────────────────────────

    const onGenerationComplete = useCallback((finalJob) => {
        setPollId(null);
        setGenerating(false);
        if (finalJob.status === 'FAILED') {
            setGenError('Generation failed. Some questions may need manual answers.');
        }
        fetchQuestions(0, activeFilter);
        fetchDetail();
    }, [activeFilter]); // eslint-disable-line

    const { job: liveJob } = useGenerationPoller(pollId, onGenerationComplete);

    // ─────────────────────────────────────────────────────────────────────────
    // Fetchers
    // ─────────────────────────────────────────────────────────────────────────

    const fetchDetail = useCallback(async () => {
        try {
            const res = await getQuestionnaire(id);
            setDetail(res.data);
            if (res.data?.aiJob?.status === 'RUNNING') {
                setGenerating(true);
                setPollId(id);
            }
        } catch {
            setPageError('Questionnaire not found.');
        } finally {
            setLoadingDetail(false);
        }
    }, [id]);

    const fetchQuestions = useCallback(async (page = 0, filter = '') => {
        setLoadingQ(true);
        setSelectedIds([]);
        try {
            // "low_confidence" is a client-side filter — request GENERATED from server
            // then filter by score < 0.50 locally
            const serverFilter = filter === 'low_confidence' ? 'GENERATED' : filter;
            const res = await getQuestions(id, {
                filter: serverFilter, page, size: PAGE_SIZE,
            });

            let content = res.data.content;
            let total   = res.data.totalElements;

            // Client-side low-confidence sub-filter
            if (filter === 'low_confidence') {
                content = content.filter((q) => q.retrievalScore != null && q.retrievalScore < 0.50);
                total   = content.length;
            }

            setQuestions(content);
            setTotalQ(total);
        } catch {
            setPageError('Failed to load questions.');
        } finally {
            setLoadingQ(false);
        }
    }, [id]);

    useEffect(() => {
        fetchDetail();
        fetchQuestions(0, '');
    }, [fetchDetail, fetchQuestions]);

    // Re-fetch when filter or page changes
    useEffect(() => {
        fetchQuestions(currentPage, activeFilter);
    }, [currentPage, activeFilter]); // eslint-disable-line

    // ─────────────────────────────────────────────────────────────────────────
    // Generation
    // ─────────────────────────────────────────────────────────────────────────

    const handleGenerate = async () => {
        setGenError('');
        setGenerating(true);
        try {
            await startGeneration(id);
            setPollId(id);
            fetchDetail();
        } catch (err) {
            setGenerating(false);
            setGenError(err.response?.data?.error || 'Failed to start generation. Please try again.');
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Single-row actions
    // ─────────────────────────────────────────────────────────────────────────

    const setRowBusy = (qid, busy) =>
        setRowLoading((prev) => ({ ...prev, [qid]: busy }));

    const patchQuestion = (updated) =>
        setQuestions((prev) => prev.map((q) => q.id === updated.id ? updated : q));

    const handleApprove = async (question) => {
        setRowBusy(question.id, true);
        try {
            const res = await approveQuestion(question.id);
            patchQuestion(res.data);
            fetchDetail();
            message.success('Approved');
        } catch {
            message.error('Could not approve. Please try again.');
        } finally {
            setRowBusy(question.id, false);
        }
    };

    const handleReject = async (question) => {
        setRowBusy(question.id, true);
        try {
            const res = await rejectQuestion(question.id);
            patchQuestion(res.data);
            fetchDetail();
            message.warning('Rejected');
        } catch {
            message.error('Could not reject. Please try again.');
        } finally {
            setRowBusy(question.id, false);
        }
    };

    const openEdit = (question) => {
        setEditTarget(question);
        setEditOpen(true);
    };

    const handleSaveEdit = async (newAnswer) => {
        if (!editTarget) return;
        setSavingEdit(true);
        try {
            const res = await editQuestion(editTarget.id, newAnswer);
            patchQuestion(res.data);
            setEditOpen(false);
            setEditTarget(null);
            fetchDetail();
            message.success('Answer saved');
        } catch {
            message.error('Could not save. Please try again.');
        } finally {
            setSavingEdit(false);
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Bulk approve
    // ─────────────────────────────────────────────────────────────────────────

    const handleBulkApprove = async () => {
        if (!selectedIds.length) return;
        try {
            const res = await bulkApprove(id, selectedIds);
            message.success(`${res.data.approved} answers approved`);
            setSelectedIds([]);
            fetchQuestions(currentPage, activeFilter);
            fetchDetail();
        } catch {
            message.error('Bulk approve failed. Please try again.');
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Export
    // ─────────────────────────────────────────────────────────────────────────

    const handleExport = async () => {
        setExporting(true);
        try {
            const res = await exportQuestionnaire(id);
            // Create a blob URL and trigger browser download
            const blob = new Blob([res.data], {
                type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            });
            const url  = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href  = url;

            // Extract filename from Content-Disposition header if present
            const disposition = res.headers?.['content-disposition'] || '';
            const match       = disposition.match(/filename="?([^";]+)"?/);
            link.download     = match ? match[1] : `questionnaire_answers.xlsx`;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            URL.revokeObjectURL(url);
            message.success('Export downloaded');
        } catch {
            message.error('Export failed. Please try again.');
        } finally {
            setExporting(false);
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Derived stats
    // ─────────────────────────────────────────────────────────────────────────

    const sc            = detail?.statusCounts || {};
    const pendingCount  = sc.PENDING    || 0;
    const genCount      = sc.GENERATED  || 0;
    const approvedCount = sc.APPROVED   || 0;
    const editedCount   = sc.EDITED     || 0;
    const rejectedCount = sc.REJECTED   || 0;
    const totalCount    = detail?.totalQuestions || 0;

    // Low confidence: questions whose retrievalScore < 0.50 — computed from current page only
    // (full count requires a DB aggregate; shown as an approximate on current page)
    const lowConfCount = questions.filter(
        (q) => q.retrievalScore != null && q.retrievalScore < 0.50
    ).length;

    const reviewedCount  = approvedCount + editedCount;
    const reviewProgress = totalCount ? Math.round((reviewedCount / totalCount) * 100) : 0;

    const activeJob  = liveJob || detail?.aiJob;
    const isRunning  = generating || activeJob?.status === 'RUNNING';
    const isComplete = activeJob?.status === 'COMPLETED';
    const canGenerate = !isRunning
        && (activeJob?.status === 'PENDING'
            || activeJob?.status === 'FAILED'
            || !activeJob);

    // ─────────────────────────────────────────────────────────────────────────
    // Row selection config
    // ─────────────────────────────────────────────────────────────────────────

    const rowSelection = {
        selectedRowKeys: selectedIds,
        onChange: (keys) => setSelectedIds(keys),
        getCheckboxProps: (record) => ({
            // Only allow selecting answerable rows
            disabled: record.status === 'PENDING',
        }),
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Row background colour
    // ─────────────────────────────────────────────────────────────────────────

    const rowClassName = (record) => {
        const band = SCORE_BAND(record.retrievalScore);
        if (band === 'medium') return 'review-row-yellow';
        if (band === 'low')    return 'review-row-red';
        return '';
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Table columns
    // ─────────────────────────────────────────────────────────────────────────

    const columns = [
        {
            title: '#',
            dataIndex: 'questionNumber',
            width: 58,
            render: (v) => v
                ? <Text code style={{ fontSize: 11 }}>{v}</Text>
                : <Text type="secondary" style={{ fontSize: 12 }}>—</Text>,
        },
        {
            title: 'Category',
            dataIndex: 'category',
            width: 130,
            ellipsis: true,
            render: (v) => v
                ? <Tag style={{ fontSize: 11, maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis' }}>{v}</Tag>
                : null,
        },
        {
            title: 'Question',
            dataIndex: 'questionText',
            width: '26%',
            render: (text) => (
                <Text style={{ fontSize: 13, lineHeight: 1.5 }}>{text}</Text>
            ),
        },
        {
            title: 'AI Answer',
            dataIndex: 'aiAnswer',
            render: (answer, record) => {
                if (!answer) {
                    return (
                        <Text type="secondary" style={{ fontSize: 12, fontStyle: 'italic' }}>
                            Not yet generated
                        </Text>
                    );
                }

                const displayAnswer =
                    (record.status === 'EDITED' || record.status === 'REJECTED') && record.manualAnswer
                        ? record.manualAnswer
                        : answer;

                const isEdited = record.status === 'EDITED' && record.manualAnswer;

                return (
                    <div>
                        <Text style={{ fontSize: 13, lineHeight: 1.5 }}>{displayAnswer}</Text>
                        {isEdited && (
                            <Tag color="cyan" style={{ marginLeft: 6, fontSize: 10 }}>Edited</Tag>
                        )}
                        {record.evidence && record.evidence !== 'N/A' && (
                            <div style={{ marginTop: 4 }}>
                                <Text type="secondary" style={{ fontSize: 11 }}>
                                    📄 {record.evidence}
                                </Text>
                            </div>
                        )}
                    </div>
                );
            },
        },
        {
            title: 'Score',
            dataIndex: 'retrievalScore',
            width: 72,
            align: 'center',
            render: (score) => <ScoreBadge score={score} />,
        },
        {
            title: 'Status',
            dataIndex: 'status',
            width: 100,
            render: (s) => {
                const cfg = STATUS_CFG[s] || { color: 'default', label: s };
                return <Tag color={cfg.color} style={{ fontSize: 11 }}>{cfg.label}</Tag>;
            },
        },
        {
            title: 'Actions',
            width: 118,
            render: (_, record) => {
                if (!record.aiAnswer) return null;
                const busy = rowLoading[record.id];
                return (
                    <Space size={2}>
                        <Tooltip title="Edit answer">
                            <Button
                                type="text" size="small"
                                icon={<EditOutlined />}
                                onClick={() => openEdit(record)}
                                disabled={busy}
                            />
                        </Tooltip>
                        <Tooltip title="Approve">
                            <Button
                                type="text" size="small"
                                icon={<CheckOutlined style={{ color: '#52c41a' }} />}
                                onClick={() => handleApprove(record)}
                                loading={busy}
                                disabled={record.status === 'APPROVED'}
                            />
                        </Tooltip>
                        <Tooltip title="Reject">
                            <Button
                                type="text" size="small" danger
                                icon={<CloseOutlined />}
                                onClick={() => handleReject(record)}
                                loading={busy}
                                disabled={record.status === 'REJECTED'}
                            />
                        </Tooltip>
                    </Space>
                );
            },
        },
    ];

    // ─────────────────────────────────────────────────────────────────────────
    // Loading / error states
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Render
    // ─────────────────────────────────────────────────────────────────────────

    return (
        <AppLayout>
            <div style={{ padding: 24, maxWidth: 1300 }}>

                {/* ── Page header ──────────────────────────────────────────────── */}
                <div style={{
                    display: 'flex', justifyContent: 'space-between',
                    alignItems: 'flex-start', marginBottom: 18,
                }}>
                    <div>
                        <Button type="text" icon={<ArrowLeftOutlined />}
                                onClick={() => navigate('/questionnaires')}
                                style={{ paddingLeft: 0, marginBottom: 4 }}>
                            All Questionnaires
                        </Button>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                            <Text style={{ fontSize: 20, fontWeight: 700 }}>
                                {detail?.filename}
                            </Text>
                            {detail?.originalFormat && (
                                <Tag color={{ XLSX: 'green', CSV: 'blue', DOCX: 'purple' }[detail.originalFormat]}>
                                    {detail.originalFormat}
                                </Tag>
                            )}
                        </div>
                        <Text type="secondary" style={{ fontSize: 13 }}>
                            {totalCount} questions · Uploaded {dayjs(detail?.uploadedAt).fromNow()}
                        </Text>
                    </div>

                    <Space>
                        <Button icon={<ReloadOutlined />}
                                onClick={() => { fetchDetail(); fetchQuestions(currentPage, activeFilter); }}>
                            Refresh
                        </Button>

                        {/* Generate button */}
                        <Button
                            type={canGenerate ? 'primary' : 'default'}
                            icon={<ThunderboltOutlined />}
                            onClick={handleGenerate}
                            loading={isRunning}
                            disabled={!canGenerate}
                            ghost={!canGenerate && isComplete}
                        >
                            {isRunning ? 'Generating…' : isComplete ? 'Re-generate' : 'Generate Answers'}
                        </Button>

                        {/* Export button — Phase 7 */}
                        <Dropdown
                            menu={{
                                items: [
                                    {
                                        key: 'xlsx',
                                        label: 'Download as Excel (.xlsx)',
                                        icon: <DownloadOutlined />,
                                        onClick: handleExport,
                                    },
                                ],
                            }}
                            trigger={['click']}
                        >
                            <Button
                                icon={<DownloadOutlined />}
                                loading={exporting}
                                disabled={totalCount === 0}
                            >
                                Export <DownOutlined />
                            </Button>
                        </Dropdown>
                    </Space>
                </div>

                {/* ── Alert strip ──────────────────────────────────────────────── */}
                {pageError && (
                    <Alert type="error" message={pageError} showIcon closable
                           style={{ marginBottom: 12 }} onClose={() => setPageError('')} />
                )}
                {genError && (
                    <Alert type="error" message={genError} showIcon closable
                           style={{ marginBottom: 12 }} onClose={() => setGenError('')} />
                )}

                {/* ── Generation progress ───────────────────────────────────────── */}
                {(isRunning || isComplete) && activeJob && (
                    <Card
                        style={{ marginBottom: 18, borderColor: isRunning ? '#1890ff' : '#52c41a' }}
                        bodyStyle={{ padding: '14px 20px' }}
                    >
                        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
                            <RobotOutlined style={{ fontSize: 18, color: isRunning ? '#1890ff' : '#52c41a' }} />
                            <Text strong style={{ fontSize: 14 }}>
                                {activeJob.statusMessage || (isRunning ? 'Generating…' : 'Complete')}
                            </Text>
                        </div>
                        <Progress
                            percent={isRunning ? (activeJob.progressPercent || 0) : 100}
                            status={isRunning ? 'active' : 'success'}
                            strokeColor={isRunning ? { from: '#108ee9', to: '#87d068' } : '#52c41a'}
                            format={() =>
                                isRunning
                                    ? `${activeJob.completedQuestions} / ${activeJob.totalQuestions}`
                                    : `${activeJob.completedQuestions} answered`
                            }
                        />
                        {isRunning && (
                            <Text type="secondary" style={{ fontSize: 12, marginTop: 6, display: 'block' }}>
                                Answers appear in the table as they're generated.
                            </Text>
                        )}
                    </Card>
                )}

                {/* ── Stats bar (Phase 6 spec: ⚠ low conf | ✅ approved | 📝 pending) */}
                <div style={{
                    display: 'flex', gap: 0,
                    marginBottom: 18,
                    border: '1px solid #f0f0f0',
                    borderRadius: 8,
                    overflow: 'hidden',
                    background: '#fff',
                }}>
                    {[
                        {
                            icon: '⚠️', label: 'Low confidence', value: lowConfCount,
                            color: '#faad14', bg: '#fffbe6',
                            action: () => { setActiveFilter('low_confidence'); setCurrentPage(0); },
                            active: activeFilter === 'low_confidence',
                        },
                        {
                            icon: '✅', label: 'Approved', value: approvedCount,
                            color: '#52c41a', bg: '#f6ffed',
                            action: () => { setActiveFilter('APPROVED'); setCurrentPage(0); },
                            active: activeFilter === 'APPROVED',
                        },
                        {
                            icon: '📝', label: 'Needs review', value: genCount,
                            color: '#1890ff', bg: '#e6f4ff',
                            action: () => { setActiveFilter('GENERATED'); setCurrentPage(0); },
                            active: activeFilter === 'GENERATED',
                        },
                        {
                            icon: '⏳', label: 'Pending', value: pendingCount,
                            color: '#8c8c8c', bg: '#fafafa',
                            action: () => { setActiveFilter('PENDING'); setCurrentPage(0); },
                            active: activeFilter === 'PENDING',
                        },
                        {
                            icon: '✏️', label: 'Edited', value: editedCount,
                            color: '#13c2c2', bg: '#e6fffb',
                            action: () => { setActiveFilter('EDITED'); setCurrentPage(0); },
                            active: activeFilter === 'EDITED',
                        },
                        {
                            icon: '✗', label: 'Rejected', value: rejectedCount,
                            color: '#ff4d4f', bg: '#fff2f0',
                            action: () => { setActiveFilter('REJECTED'); setCurrentPage(0); },
                            active: activeFilter === 'REJECTED',
                        },
                    ].map(({ icon, label, value, color, bg, action, active }, i, arr) => (
                        <button
                            key={label}
                            onClick={action}
                            style={{
                                flex: 1,
                                border: 'none',
                                borderRight: i < arr.length - 1 ? '1px solid #f0f0f0' : 'none',
                                background: active ? bg : '#fff',
                                cursor: 'pointer',
                                padding: '12px 8px',
                                transition: 'background 0.15s',
                                outline: active ? `2px solid ${color}` : 'none',
                                outlineOffset: -2,
                            }}
                        >
                            <div style={{ fontSize: 18 }}>{icon}</div>
                            <div style={{ fontWeight: 700, fontSize: 20, color, lineHeight: 1.1 }}>
                                {value}
                            </div>
                            <div style={{ fontSize: 11, color: '#8c8c8c', marginTop: 2 }}>{label}</div>
                        </button>
                    ))}
                </div>

                {/* ── Review progress bar ───────────────────────────────────────── */}
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
                            trailColor="#f0f0f0"
                            size="small"
                        />
                    </div>
                )}

                {/* ── Bulk action toolbar (appears when rows selected) ──────────── */}
                {selectedIds.length > 0 && (
                    <div style={{
                        display: 'flex', alignItems: 'center', gap: 12,
                        padding: '10px 16px', marginBottom: 12,
                        background: '#e6f4ff', borderRadius: 6,
                        border: '1px solid #91caff',
                    }}>
                        <Text strong style={{ fontSize: 13 }}>
                            {selectedIds.length} question{selectedIds.length > 1 ? 's' : ''} selected
                        </Text>
                        <Button
                            type="primary" size="small"
                            icon={<CheckCircleOutlined />}
                            onClick={handleBulkApprove}
                        >
                            Approve selected
                        </Button>
                        <Button size="small" onClick={() => setSelectedIds([])}>
                            Clear selection
                        </Button>
                    </div>
                )}

                {/* ── Filter row ────────────────────────────────────────────────── */}
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 10 }}>
                    <Space>
                        <FilterOutlined style={{ color: '#8c8c8c' }} />
                        <Select
                            value={activeFilter}
                            style={{ width: 230 }}
                            size="small"
                            onChange={(val) => { setActiveFilter(val); setCurrentPage(0); }}
                        >
                            {FILTER_OPTIONS.map((opt) => (
                                <Option key={opt.value} value={opt.value}>{opt.label}</Option>
                            ))}
                        </Select>
                        {activeFilter && (
                            <Button size="small" type="link"
                                    onClick={() => { setActiveFilter(''); setCurrentPage(0); }}>
                                Clear filter
                            </Button>
                        )}
                    </Space>
                    <Text type="secondary" style={{ fontSize: 12, alignSelf: 'center' }}>
                        Showing {questions.length} of {totalQ} questions
                    </Text>
                </div>

                {/* ── Questions table ───────────────────────────────────────────── */}
                <Table
                    dataSource={questions}
                    columns={columns}
                    rowKey="id"
                    loading={loadingQ}
                    size="small"
                    rowSelection={rowSelection}
                    rowClassName={rowClassName}
                    pagination={{
                        current:         currentPage + 1,
                        pageSize:        PAGE_SIZE,
                        total:           totalQ,
                        showSizeChanger: false,
                        showTotal: (total, range) =>
                            `${range[0]}–${range[1]} of ${total} questions`,
                        onChange: (antPage) => {
                            setCurrentPage(antPage - 1);
                            window.scrollTo({ top: 0, behavior: 'smooth' });
                        },
                    }}
                    locale={{
                        emptyText: (
                            <div style={{ padding: 48, textAlign: 'center' }}>
                                <RobotOutlined style={{ fontSize: 36, color: '#d9d9d9', marginBottom: 12 }} />
                                <p style={{ color: '#8c8c8c', marginBottom: pendingCount > 0 ? 16 : 0 }}>
                                    {activeFilter
                                        ? `No questions match "${FILTER_OPTIONS.find(f => f.value === activeFilter)?.label || activeFilter}"`
                                        : pendingCount > 0
                                            ? 'Click "Generate Answers" to have AI answer all questions.'
                                            : 'No questions found'}
                                </p>
                                {!activeFilter && pendingCount > 0 && canGenerate && (
                                    <Button type="primary" icon={<ThunderboltOutlined />}
                                            onClick={handleGenerate}>
                                        Generate Answers
                                    </Button>
                                )}
                            </div>
                        ),
                    }}
                />

                {/* ── Confidence legend ─────────────────────────────────────────── */}
                <div style={{
                    display: 'flex', gap: 20, marginTop: 12,
                    padding: '8px 16px', background: '#fafafa',
                    borderRadius: 6, border: '1px solid #f0f0f0',
                    alignItems: 'center',
                }}>
                    <Text type="secondary" style={{ fontSize: 12, fontWeight: 600 }}>
                        Confidence score:
                    </Text>
                    {[
                        { color: BAND_COLORS.high.badge,   label: '≥ 80% — High' },
                        { color: BAND_COLORS.medium.badge, label: '50–79% — Review' },
                        { color: BAND_COLORS.low.badge,    label: '< 50% — Low'  },
                    ].map(({ color, label }) => (
                        <Space key={label} size={6}>
                            <div style={{
                                width: 10, height: 10, borderRadius: '50%',
                                background: color, flexShrink: 0,
                            }} />
                            <Text type="secondary" style={{ fontSize: 12 }}>{label}</Text>
                        </Space>
                    ))}
                </div>

                {/* ── Edit Modal ────────────────────────────────────────────────── */}
                <EditModal
                    question={editTarget}
                    open={editOpen}
                    onSave={handleSaveEdit}
                    onCancel={() => { setEditOpen(false); setEditTarget(null); }}
                    saving={savingEdit}
                />
            </div>

            {/* ── Row colour styles ─────────────────────────────────────────── */}
            <style>{`
        .review-row-yellow td { background-color: #fffbe6 !important; }
        .review-row-yellow:hover td { background-color: #fff1b8 !important; }
        .review-row-red td { background-color: #fff2f0 !important; }
        .review-row-red:hover td { background-color: #ffccc7 !important; }
      `}</style>
        </AppLayout>
    );
}