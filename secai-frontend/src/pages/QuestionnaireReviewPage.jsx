// src/pages/QuestionnaireReviewPage.jsx  — COMPLETE POLISHED REPLACEMENT
// Phase 5–7 review page with all polish improvements:
//   ✅ Rich evidence panel with source chips and confidence explanation
//   ✅ Detailed generation progress with step pipeline
//   ✅ Keyboard shortcuts (A=approve, E=edit, R=reject, ↓/↑=navigate)
//   ✅ Approved answer library badges with reuse metadata
//   ✅ AI "retrieval" display showing which docs were searched
//   ✅ Questionnaire summary after generation completes
//   ✅ Value metrics throughout

import {
    useEffect, useState, useCallback, useRef,
} from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
    Table, Tag, Button, Typography, Alert, Spin, Card,
    Modal, Input, Space, Tooltip, Badge, Select, Row, Col,
    Statistic, message, Popconfirm, Dropdown, Divider,
    Progress, Steps,
} from 'antd';
import {
    ArrowLeftOutlined, CheckOutlined, CloseOutlined,
    EditOutlined, ReloadOutlined, CheckCircleOutlined,
    ThunderboltOutlined, DownloadOutlined, FilterOutlined,
    WarningOutlined, ExclamationCircleOutlined, DownOutlined,
    LoadingOutlined, DatabaseOutlined, SearchOutlined,
    FileTextOutlined, StarOutlined, RobotOutlined,
    SafetyCertificateOutlined,
} from '@ant-design/icons';
import AppLayout from '../components/AppLayout';
import EvidencePanel from '../components/EvidencePanel';
import GenerationProgress from '../components/GenerationProgress';
import QuestionnaireSummary from '../components/QuestionnaireSummary';
import {
    getQuestionnaire, getQuestions, startGeneration,
    editQuestion, approveQuestion, rejectQuestion,
    bulkApprove, exportQuestionnaire,
} from '../api/questionnaires';
import { reuseLibraryAnswer }    from '../api/library';
import { useGenerationPoller }   from '../hooks/useGenerationPoller';
import { useLibraryMatches }     from '../hooks/useLibraryMatches';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';

dayjs.extend(relativeTime);

const { Text, Paragraph } = Typography;
const { TextArea } = Input;
const { Option }   = Select;

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────

const BAND = (score) => {
    if (score == null)  return null;
    if (score >= 0.85)  return 'HIGH';
    if (score >= 0.60)  return 'MEDIUM';
    return 'LOW';
};

const BAND_COLORS = {
    HIGH:   { badge: '#52c41a', bg: '#f6ffed', row: '#f6ffed' },
    MEDIUM: { badge: '#faad14', bg: '#fffbe6', row: '#fffbe6' },
    LOW:    { badge: '#ff4d4f', bg: '#fff2f0', row: '#fff2f0' },
};

const STATUS_CFG = {
    PENDING:   { color: 'default',    label: 'Pending'    },
    GENERATED: { color: 'processing', label: 'AI Answer'  },
    APPROVED:  { color: 'success',    label: 'Approved'   },
    EDITED:    { color: 'cyan',       label: 'Edited'     },
    REJECTED:  { color: 'error',      label: 'Rejected'   },
};

const FILTER_OPTIONS = [
    { value: '',               label: 'All questions'           },
    { value: 'GENERATED',     label: 'AI answers (unreviewed)' },
    { value: 'low_confidence', label: '⚠ Low confidence (< 60%)' },
    { value: 'PENDING',        label: 'Pending (no answer yet)' },
    { value: 'APPROVED',       label: 'Approved'                },
    { value: 'EDITED',         label: 'Edited'                  },
    { value: 'REJECTED',       label: 'Rejected'                },
];

// ─────────────────────────────────────────────────────────────────────────────
// Library Match Card — inline inside the AI Answer column
// ─────────────────────────────────────────────────────────────────────────────

function LibraryMatchCard({ match, questionId, onReused, onDismiss }) {
    const [loading, setLoading]     = useState(false);
    const [dismissed, setDismissed] = useState(false);

    if (dismissed || !match) return null;

    const pct      = match.similarityPercent;
    const pctColor = pct >= 90 ? '#52c41a' : pct >= 82 ? '#73d13d' : '#faad14';

    const handleReuse = async () => {
        setLoading(true);
        try {
            await reuseLibraryAnswer(questionId, match.libraryEntryId);
            onReused?.();
            message.success('Answer reused from library');
        } catch {
            message.error('Could not reuse answer. Please try again.');
        } finally {
            setLoading(false);
        }
    };

    return (
        <div style={{
            margin:       '0 0 8px 0',
            padding:      '10px 12px',
            background:   '#f6ffed',
            border:       '1px solid #b7eb8f',
            borderLeft:   `3px solid ${pctColor}`,
            borderRadius: 6,
            fontSize:     12,
        }}>
            <div style={{
                display:        'flex',
                justifyContent: 'space-between',
                alignItems:     'center',
                marginBottom:   6,
            }}>
                <Space size={6}>
                    <CheckCircleOutlined style={{ color: pctColor, fontSize: 12 }} />
                    <Text strong style={{ fontSize: 11, color: '#135200' }}>
                        Approved answer found
                    </Text>
                    <span style={{
                        background:   pctColor,
                        color:        '#fff',
                        borderRadius: 8,
                        padding:      '0 7px',
                        fontSize:     10,
                        fontWeight:   700,
                    }}>
            {pct}% match
          </span>
                </Space>
                <Button
                    type="text" size="small"
                    icon={<CloseOutlined style={{ fontSize: 10 }} />}
                    style={{ padding: '0 4px', height: 18 }}
                    onClick={() => { setDismissed(true); onDismiss?.(); }}
                />
            </div>

            <div style={{ marginBottom: 6 }}>
                <Text style={{ fontSize: 12, fontWeight: 500, lineHeight: 1.5 }}>
                    {match.answerText.length > 140
                        ? match.answerText.slice(0, 140) + '…'
                        : match.answerText}
                </Text>
            </div>

            <div style={{ marginBottom: 8, display: 'flex', gap: 12 }}>
                {match.evidence && match.evidence !== 'N/A' && (
                    <Text type="secondary" style={{ fontSize: 10 }}>📄 {match.evidence}</Text>
                )}
                {match.approvedByEmail && (
                    <Text type="secondary" style={{ fontSize: 10 }}>
                        ✓ {match.approvedByEmail}
                        {match.approvedAt && ` · ${dayjs(match.approvedAt).format('MMM YYYY')}`}
                    </Text>
                )}
            </div>

            <Space size={6}>
                <Button
                    type="primary" size="small"
                    style={{ fontSize: 11, height: 24 }}
                    loading={loading}
                    onClick={handleReuse}
                >
                    Reuse this answer
                </Button>
                <Button
                    size="small"
                    style={{ fontSize: 11, height: 24 }}
                    onClick={() => { setDismissed(true); onDismiss?.(); }}
                >
                    Dismiss
                </Button>
            </Space>
        </div>
    );
}

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

                    {/* Question context */}
                    <div style={{
                        background:   '#f5f5f5',
                        borderRadius: 6,
                        padding:      '10px 14px',
                        borderLeft:   '3px solid #1890ff',
                    }}>
                        <Text type="secondary" style={{ fontSize: 11, fontWeight: 600, textTransform: 'uppercase' }}>
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
                                AI answer <span style={{ color: '#8c8c8c' }}>(for reference)</span>
                            </Text>
                            <div style={{
                                padding:      '8px 12px',
                                background:   '#e6f4ff',
                                borderRadius: 4,
                                fontSize:     13,
                                color:        '#0958d9',
                                lineHeight:   1.6,
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
                    </div>
                </div>
            )}
        </Modal>
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Keyboard shortcut helper — shown in a small legend above the table
// ─────────────────────────────────────────────────────────────────────────────

function KeyboardLegend() {
    return (
        <div style={{
            display:      'flex',
            gap:          14,
            padding:      '6px 12px',
            background:   '#fafafa',
            borderRadius: 6,
            border:       '1px solid #f0f0f0',
            fontSize:     11,
            color:        '#8c8c8c',
            flexWrap:     'wrap',
        }}>
            <Text type="secondary" style={{ fontSize: 11, fontWeight: 600 }}>
                Keyboard shortcuts:
            </Text>
            {[
                { key: 'A', label: 'Approve' },
                { key: 'E', label: 'Edit' },
                { key: 'R', label: 'Reject' },
                { key: '↓', label: 'Next' },
                { key: '↑', label: 'Prev' },
            ].map(({ key, label }) => (
                <span key={key}>
          <kbd style={{
              background:   '#fff',
              border:       '1px solid #d9d9d9',
              borderRadius: 3,
              padding:      '1px 5px',
              fontSize:     10,
              fontFamily:   'monospace',
              boxShadow:    '0 1px 0 rgba(0,0,0,0.12)',
          }}>
            {key}
          </kbd>
                    {' '}{label}
        </span>
            ))}
        </div>
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
    const [activeFilter, setActiveFilter]   = useState('');
    const [loadingDetail, setLoadingDetail] = useState(true);
    const [loadingQ, setLoadingQ]           = useState(false);
    const [pageError, setPageError]         = useState('');

    // ── Generation ────────────────────────────────────────────────────────────
    const [generating, setGenerating]       = useState(false);
    const [genError, setGenError]           = useState('');
    const [pollId, setPollId]               = useState(null);
    const [generationJustCompleted, setGenerationJustCompleted] = useState(false);

    // ── Selection & keyboard ──────────────────────────────────────────────────
    const [selectedIds, setSelectedIds]     = useState([]);
    const [focusedRowIdx, setFocusedRowIdx] = useState(null);

    // ── Edit modal ────────────────────────────────────────────────────────────
    const [editTarget, setEditTarget]       = useState(null);
    const [editOpen, setEditOpen]           = useState(false);
    const [savingEdit, setSavingEdit]       = useState(false);

    // ── Per-row loading ───────────────────────────────────────────────────────
    const [rowLoading, setRowLoading]       = useState({});

    // ── Export ────────────────────────────────────────────────────────────────
    const [exporting, setExporting]         = useState(false);

    // ── Library ───────────────────────────────────────────────────────────────
    const libraryMatches                    = useLibraryMatches(questions);
    const [dismissedMatches, setDismissedMatches] = useState(new Set());

    // ─────────────────────────────────────────────────────────────────────────
    // Generation polling
    // ─────────────────────────────────────────────────────────────────────────

    const onGenerationComplete = useCallback((finalJob) => {
        setPollId(null);
        setGenerating(false);
        if (finalJob.status === 'FAILED') {
            setGenError('Generation failed. Some questions may need manual answers.');
        } else {
            setGenerationJustCompleted(true);
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
            const serverFilter = filter === 'low_confidence' ? 'GENERATED' : filter;
            const res = await getQuestions(id, {
                filter: serverFilter, page, size: PAGE_SIZE,
            });
            let content = res.data.content;
            let total   = res.data.totalElements;
            if (filter === 'low_confidence') {
                content = content.filter(q => q.retrievalScore != null && q.retrievalScore < 0.60);
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

    useEffect(() => { fetchDetail(); fetchQuestions(0, ''); }, [fetchDetail, fetchQuestions]);
    useEffect(() => { fetchQuestions(currentPage, activeFilter); }, [currentPage, activeFilter]); // eslint-disable-line

    // ─────────────────────────────────────────────────────────────────────────
    // Keyboard shortcuts
    // ─────────────────────────────────────────────────────────────────────────

    useEffect(() => {
        if (editOpen) return; // don't fire shortcuts when modal is open

        const handle = (e) => {
            // Don't fire if user is typing in a field
            if (['INPUT', 'TEXTAREA', 'SELECT'].includes(e.target.tagName)) return;

            const idx = focusedRowIdx;
            const q   = idx != null ? questions[idx] : null;

            if (e.key === 'ArrowDown' || e.key === 'j') {
                e.preventDefault();
                setFocusedRowIdx(i => Math.min((i ?? -1) + 1, questions.length - 1));
            } else if (e.key === 'ArrowUp' || e.key === 'k') {
                e.preventDefault();
                setFocusedRowIdx(i => Math.max((i ?? questions.length) - 1, 0));
            } else if ((e.key === 'a' || e.key === 'A') && q?.aiAnswer) {
                e.preventDefault();
                handleApprove(q);
            } else if ((e.key === 'e' || e.key === 'E') && q?.aiAnswer) {
                e.preventDefault();
                openEdit(q);
            } else if ((e.key === 'r' || e.key === 'R') && q?.aiAnswer) {
                e.preventDefault();
                handleReject(q);
            }
        };

        window.addEventListener('keydown', handle);
        return () => window.removeEventListener('keydown', handle);
    }, [editOpen, focusedRowIdx, questions]); // eslint-disable-line

    // ─────────────────────────────────────────────────────────────────────────
    // Generation trigger
    // ─────────────────────────────────────────────────────────────────────────

    const handleGenerate = async () => {
        setGenError('');
        setGenerating(true);
        setGenerationJustCompleted(false);
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
    // Review actions
    // ─────────────────────────────────────────────────────────────────────────

    const setRowBusy = (qid, busy) =>
        setRowLoading(prev => ({ ...prev, [qid]: busy }));

    const patchQuestion = (updated) =>
        setQuestions(prev => prev.map(q => q.id === updated.id ? updated : q));

    const handleApprove = async (question) => {
        if (!question?.aiAnswer) return;
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
        if (!question?.aiAnswer) return;
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

    const handleExport = async () => {
        setExporting(true);
        try {
            const res  = await exportQuestionnaire(id);
            const blob = new Blob([res.data], {
                type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            });
            const url  = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href  = url;
            const disp = res.headers?.['content-disposition'] || '';
            const m    = disp.match(/filename="?([^";]+)"?/);
            link.download = m ? m[1] : `questionnaire_answers.xlsx`;
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
    const aiAnswered    = genCount + approvedCount + editedCount + rejectedCount;
    const noEvidence    = pendingCount; // after generation, PENDING = no evidence found

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
    // Table row styling
    // ─────────────────────────────────────────────────────────────────────────

    const rowClassName = (record, idx) => {
        const classes = [];
        const band    = BAND(record.retrievalScore);
        if (band === 'MEDIUM') classes.push('review-row-yellow');
        if (band === 'LOW')    classes.push('review-row-red');
        if (idx === focusedRowIdx) classes.push('review-row-focused');
        return classes.join(' ');
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Table columns
    // ─────────────────────────────────────────────────────────────────────────

    const columns = [
        {
            title:     '#',
            dataIndex: 'questionNumber',
            width:     58,
            render: (v) => v
                ? <Text code style={{ fontSize: 11 }}>{v}</Text>
                : <Text type="secondary" style={{ fontSize: 12 }}>—</Text>,
        },
        {
            title:     'Category',
            dataIndex: 'category',
            width:     130,
            ellipsis:  true,
            render: (v) => v
                ? <Tag style={{ fontSize: 11, maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis' }}>{v}</Tag>
                : null,
        },
        {
            title:     'Question',
            dataIndex: 'questionText',
            width:     '24%',
            render: (text) => (
                <Text style={{ fontSize: 13, lineHeight: 1.5 }}>{text}</Text>
            ),
        },
        {
            // Rich evidence + answer + library match
            title:     'AI Answer & Evidence',
            dataIndex: 'aiAnswer',
            render: (answer, record, idx) => {
                const libMatch  = libraryMatches[record.id];
                const showMatch = libMatch
                    && record.status === 'GENERATED'
                    && !dismissedMatches.has(record.id);

                const displayAnswer =
                    (record.status === 'EDITED' || record.status === 'REJECTED') && record.manualAnswer
                        ? record.manualAnswer
                        : answer;

                return (
                    <div>
                        {/* Library match card — shown above the answer */}
                        {showMatch && (
                            <LibraryMatchCard
                                match={libMatch}
                                questionId={record.id}
                                onReused={() => {
                                    fetchQuestions(currentPage, activeFilter);
                                    fetchDetail();
                                    setDismissedMatches(prev => new Set([...prev, record.id]));
                                }}
                                onDismiss={() =>
                                    setDismissedMatches(prev => new Set([...prev, record.id]))
                                }
                            />
                        )}

                        {/* Rich evidence panel */}
                        <EvidencePanel
                            answer={displayAnswer}
                            evidence={record.evidence}
                            retrievalScore={record.retrievalScore}
                            status={record.status}
                            fromLibrary={showMatch === false && !!libMatch}
                        />
                    </div>
                );
            },
        },
        {
            title:     'Status',
            dataIndex: 'status',
            width:     100,
            render: (s) => {
                const cfg = STATUS_CFG[s] || { color: 'default', label: s };
                return (
                    <Tag color={cfg.color} style={{ fontSize: 11 }}>
                        {cfg.label}
                    </Tag>
                );
            },
        },
        {
            title: (
                <Tooltip title="A=Approve  E=Edit  R=Reject">
          <span>Actions <kbd style={{
              background: '#f5f5f5', border: '1px solid #d9d9d9',
              borderRadius: 3, padding: '0 4px', fontSize: 9,
          }}>⌨</kbd></span>
                </Tooltip>
            ),
            width:  118,
            render: (_, record, idx) => {
                if (!record.aiAnswer) return null;
                const busy = rowLoading[record.id];
                return (
                    <Space size={2}>
                        <Tooltip title="Edit (E)">
                            <Button
                                type="text" size="small"
                                icon={<EditOutlined />}
                                onClick={() => { setFocusedRowIdx(idx); openEdit(record); }}
                                disabled={busy}
                            />
                        </Tooltip>
                        <Tooltip title="Approve (A)">
                            <Button
                                type="text" size="small"
                                icon={<CheckOutlined style={{ color: '#52c41a' }} />}
                                onClick={() => { setFocusedRowIdx(idx); handleApprove(record); }}
                                loading={busy}
                                disabled={record.status === 'APPROVED'}
                            />
                        </Tooltip>
                        <Tooltip title="Reject (R)">
                            <Button
                                type="text" size="small" danger
                                icon={<CloseOutlined />}
                                onClick={() => { setFocusedRowIdx(idx); handleReject(record); }}
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
                    display:        'flex',
                    justifyContent: 'space-between',
                    alignItems:     'flex-start',
                    marginBottom:   18,
                }}>
                    <div>
                        <Button type="text" icon={<ArrowLeftOutlined />}
                                onClick={() => navigate('/questionnaires')}
                                style={{ paddingLeft: 0, marginBottom: 4 }}>
                            All Questionnaires
                        </Button>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                            <Text style={{ fontSize: 20, fontWeight: 700 }}>{detail?.filename}</Text>
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
                        <Button
                            type={canGenerate ? 'primary' : 'default'}
                            icon={<ThunderboltOutlined />}
                            onClick={handleGenerate}
                            loading={isRunning}
                            disabled={!canGenerate}
                        >
                            {isRunning ? 'Generating…' : isComplete ? 'Re-generate' : 'Generate Answers'}
                        </Button>
                        <Dropdown
                            menu={{
                                items: [{
                                    key: 'xlsx',
                                    label: 'Download as Excel (.xlsx)',
                                    icon: <DownloadOutlined />,
                                    onClick: handleExport,
                                }],
                            }}
                            trigger={['click']}
                        >
                            <Button icon={<DownloadOutlined />} loading={exporting} disabled={totalCount === 0}>
                                Export <DownOutlined />
                            </Button>
                        </Dropdown>
                    </Space>
                </div>

                {/* ── Alerts ───────────────────────────────────────────────────── */}
                {pageError && (
                    <Alert type="error" message={pageError} showIcon closable
                           style={{ marginBottom: 12 }} onClose={() => setPageError('')} />
                )}
                {genError && (
                    <Alert type="error" message={genError} showIcon closable
                           style={{ marginBottom: 12 }} onClose={() => setGenError('')} />
                )}

                {/* ── Generation progress (replaces plain spinner) ─────────────── */}
                {(isRunning || (isComplete && !generationJustCompleted)) && (
                    <GenerationProgress
                        status={activeJob?.status}
                        totalQuestions={activeJob?.totalQuestions ?? totalCount}
                        completedQuestions={activeJob?.completedQuestions ?? 0}
                        progressPercent={activeJob?.progressPercent ?? 0}
                        statusMessage={activeJob?.statusMessage}
                    />
                )}

                {/* ── Questionnaire summary (shown immediately after generation) ── */}
                {generationJustCompleted && (
                    <div style={{ marginBottom: 20 }}>
                        <QuestionnaireSummary
                            totalQuestions={totalCount}
                            aiAnswered={aiAnswered}
                            needsReview={genCount}
                            noEvidence={noEvidence}
                            approvedCount={approvedCount}
                            editedCount={editedCount}
                            rejectedCount={rejectedCount}
                            filename={detail?.filename}
                            onExport={handleExport}
                            onReview={() => {
                                setGenerationJustCompleted(false);
                                setActiveFilter('GENERATED');
                            }}
                            canExport={true}
                        />
                    </div>
                )}

                {/* ── Stats filter strip ───────────────────────────────────────── */}
                <div style={{
                    display:      'flex',
                    gap:          0,
                    marginBottom: 16,
                    border:       '1px solid #f0f0f0',
                    borderRadius: 8,
                    overflow:     'hidden',
                    background:   '#fff',
                }}>
                    {[
                        { icon: '⚠️', label: 'Low confidence', value: questions.filter(q => q.retrievalScore != null && q.retrievalScore < 0.60).length,
                            color: '#faad14', bg: '#fffbe6',
                            action: () => { setActiveFilter('low_confidence'); setCurrentPage(0); },
                            active: activeFilter === 'low_confidence' },
                        { icon: '✅', label: 'Approved', value: approvedCount,
                            color: '#52c41a', bg: '#f6ffed',
                            action: () => { setActiveFilter('APPROVED'); setCurrentPage(0); },
                            active: activeFilter === 'APPROVED' },
                        { icon: '📝', label: 'Needs review', value: genCount,
                            color: '#1890ff', bg: '#e6f4ff',
                            action: () => { setActiveFilter('GENERATED'); setCurrentPage(0); },
                            active: activeFilter === 'GENERATED' },
                        { icon: '⏳', label: 'Pending', value: pendingCount,
                            color: '#8c8c8c', bg: '#fafafa',
                            action: () => { setActiveFilter('PENDING'); setCurrentPage(0); },
                            active: activeFilter === 'PENDING' },
                        { icon: '✏️', label: 'Edited', value: editedCount,
                            color: '#13c2c2', bg: '#e6fffb',
                            action: () => { setActiveFilter('EDITED'); setCurrentPage(0); },
                            active: activeFilter === 'EDITED' },
                        { icon: '✗', label: 'Rejected', value: rejectedCount,
                            color: '#ff4d4f', bg: '#fff2f0',
                            action: () => { setActiveFilter('REJECTED'); setCurrentPage(0); },
                            active: activeFilter === 'REJECTED' },
                    ].map(({ icon, label, value, color, bg, action, active }, i, arr) => (
                        <button
                            key={label}
                            onClick={action}
                            style={{
                                flex:        1,
                                border:      'none',
                                borderRight: i < arr.length - 1 ? '1px solid #f0f0f0' : 'none',
                                background:  active ? bg : '#fff',
                                cursor:      'pointer',
                                padding:     '12px 8px',
                                transition:  'background 0.15s',
                                outline:     active ? `2px solid ${color}` : 'none',
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

                {/* ── Review progress bar ──────────────────────────────────────── */}
                {reviewedCount > 0 && (
                    <div style={{ marginBottom: 14 }}>
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

                {/* ── Keyboard legend ──────────────────────────────────────────── */}
                {questions.some(q => q.aiAnswer) && (
                    <div style={{ marginBottom: 10 }}>
                        <KeyboardLegend />
                    </div>
                )}

                {/* ── Bulk action toolbar ──────────────────────────────────────── */}
                {selectedIds.length > 0 && (
                    <div style={{
                        display:      'flex',
                        alignItems:   'center',
                        gap:          12,
                        padding:      '10px 16px',
                        marginBottom: 12,
                        background:   '#e6f4ff',
                        borderRadius: 6,
                        border:       '1px solid #91caff',
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
                        <Button size="small" onClick={() => setSelectedIds([])}>Clear selection</Button>
                    </div>
                )}

                {/* ── Filter + total ───────────────────────────────────────────── */}
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 10 }}>
                    <Space>
                        <FilterOutlined style={{ color: '#8c8c8c' }} />
                        <Select
                            value={activeFilter}
                            style={{ width: 240 }}
                            size="small"
                            onChange={(val) => { setActiveFilter(val); setCurrentPage(0); }}
                        >
                            {FILTER_OPTIONS.map(opt => (
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

                {/* ── Questions table ──────────────────────────────────────────── */}
                <Table
                    dataSource={questions}
                    columns={columns}
                    rowKey="id"
                    loading={loadingQ}
                    size="small"
                    rowSelection={{
                        selectedRowKeys: selectedIds,
                        onChange:        (keys) => setSelectedIds(keys),
                        getCheckboxProps: (record) => ({ disabled: record.status === 'PENDING' }),
                    }}
                    rowClassName={rowClassName}
                    onRow={(record, idx) => ({
                        onClick:    () => setFocusedRowIdx(idx),
                        style:      { cursor: 'default' },
                    })}
                    pagination={{
                        current:         currentPage + 1,
                        pageSize:        PAGE_SIZE,
                        total:           totalQ,
                        showSizeChanger: false,
                        showTotal:       (total, range) =>
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
                                        ? `No questions match this filter`
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

                {/* ── Confidence legend ────────────────────────────────────────── */}
                <div style={{
                    display:      'flex',
                    gap:          20,
                    marginTop:    12,
                    padding:      '8px 16px',
                    background:   '#fafafa',
                    borderRadius: 6,
                    border:       '1px solid #f0f0f0',
                    alignItems:   'center',
                }}>
                    <Text type="secondary" style={{ fontSize: 12, fontWeight: 600 }}>
                        Confidence score:
                    </Text>
                    {[
                        { color: '#52c41a', label: '≥ 85% — High' },
                        { color: '#faad14', label: '60–84% — Review' },
                        { color: '#ff4d4f', label: '< 60% — Low (likely missing document)' },
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

                {/* ── Library reuse count indicator ───────────────────────────── */}
                {Object.values(libraryMatches).filter(Boolean).length > 0 && (
                    <div style={{
                        marginTop:    10,
                        padding:      '8px 16px',
                        background:   '#f6ffed',
                        borderRadius: 6,
                        border:       '1px solid #b7eb8f',
                        display:      'flex',
                        alignItems:   'center',
                        gap:          8,
                    }}>
                        <StarOutlined style={{ color: '#52c41a' }} />
                        <Text style={{ fontSize: 12, color: '#135200' }}>
                            <strong>
                                {Object.values(libraryMatches).filter(Boolean).length}
                            </strong>
                            {' '}questions on this page have approved answers from your library
                            — click "Reuse" to apply them instantly.
                        </Text>
                    </div>
                )}

                {/* ── Edit Modal ───────────────────────────────────────────────── */}
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
        .review-row-focused td { outline: 2px solid #1890ff; outline-offset: -1px; }
      `}</style>
        </AppLayout>
    );
}