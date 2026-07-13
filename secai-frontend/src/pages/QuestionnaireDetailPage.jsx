// src/pages/QuestionnaireDetailPage.jsx
import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import {
    Table, Tag, Button, Typography, Alert, Spin,
    Card, Statistic, Row, Col, Select, Space,
    Tooltip, Badge, Popconfirm,
} from 'antd';
import {
    ArrowLeftOutlined, WarningOutlined,
    QuestionCircleOutlined, ReloadOutlined,
    DeleteOutlined, RobotOutlined,
} from '@ant-design/icons';
import { getQuestionnaire, getQuestions, deleteQuestionnaire } from '../api/questionnaires';
import AppLayout from '../components/AppLayout';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';

dayjs.extend(relativeTime);

const { Title, Text } = Typography;
const { Option } = Select;

const STATUS_COLOR = {
    PENDING:   'default',
    GENERATED: 'processing',
    APPROVED:  'success',
    EDITED:    'cyan',
    REJECTED:  'error',
};

const Q_STATUS_COLOR = {
    PARSED:      'blue',
    GENERATING:  'processing',
    COMPLETED:   'success',
    FAILED:      'error',
    UPLOADED:    'default',
};

export default function QuestionnaireDetailPage() {
    const { id } = useParams();
    const navigate = useNavigate();

    const [detail, setDetail]         = useState(null);
    const [questions, setQuestions]   = useState([]);
    const [totalQ, setTotalQ]         = useState(0);
    const [currentPage, setCurrentPage] = useState(0);
    const [pageSize]                  = useState(50);
    const [statusFilter, setStatusFilter] = useState('');
    const [loadingDetail, setLoadingDetail] = useState(true);
    const [loadingQ, setLoadingQ]     = useState(false);
    const [pageError, setPageError]   = useState('');

    // ── Fetch questionnaire detail ───────────────────────────────────────
    const fetchDetail = useCallback(async () => {
        try {
            const res = await getQuestionnaire(id);
            setDetail(res.data);
        } catch {
            setPageError('Questionnaire not found or you do not have access.');
        } finally {
            setLoadingDetail(false);
        }
    }, [id]);

    // ── Fetch questions (paginated) ──────────────────────────────────────
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

    // Refetch when filter or page changes
    useEffect(() => {
        fetchQuestions(currentPage, statusFilter);
    }, [currentPage, statusFilter, fetchQuestions]);

    // ── Delete ───────────────────────────────────────────────────────────
    const handleDelete = async () => {
        try {
            await deleteQuestionnaire(id);
            navigate('/questionnaires');
        } catch {
            setPageError('Failed to delete questionnaire.');
        }
    };

    // ── Table columns ────────────────────────────────────────────────────
    const columns = [
        {
            title: '#',
            dataIndex: 'questionNumber',
            width: 65,
            render: (v) => v
                ? <Text code style={{ fontSize: 12 }}>{v}</Text>
                : <Text type="secondary" style={{ fontSize: 12 }}>—</Text>,
        },
        {
            title: 'Category',
            dataIndex: 'category',
            width: 150,
            ellipsis: true,
            render: (v) => v
                ? <Tag style={{ fontSize: 11 }}>{v}</Tag>
                : null,
        },
        {
            title: 'Question',
            dataIndex: 'questionText',
            render: (text) => (
                <Text style={{ fontSize: 13 }}>{text}</Text>
            ),
        },
        {
            title: 'Status',
            dataIndex: 'status',
            width: 105,
            render: (s) => (
                <Tag color={STATUS_COLOR[s] || 'default'} style={{ fontSize: 11 }}>
                    {s}
                </Tag>
            ),
        },
    ];

    // ── Loading state ────────────────────────────────────────────────────
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

    // ── Stats ─────────────────────────────────────────────────────────────
    const statusCounts = detail?.statusCounts || {};
    const pendingCount   = statusCounts.PENDING   || 0;
    const generatedCount = statusCounts.GENERATED || 0;
    const approvedCount  = statusCounts.APPROVED  || 0;
    const editedCount    = statusCounts.EDITED    || 0;
    const rejectedCount  = statusCounts.REJECTED  || 0;

    return (
        <AppLayout>
            <div style={{ padding: 24, maxWidth: 1100 }}>

                {/* Header row */}
                <div style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'flex-start',
                    marginBottom: 20,
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
                            {detail?.originalFormat && (
                                <Tag
                                    color={{ XLSX: 'green', CSV: 'blue', DOCX: 'purple' }[detail.originalFormat]}
                                    style={{ marginLeft: 10, fontSize: 12 }}
                                >
                                    {detail.originalFormat}
                                </Tag>
                            )}
                        </Title>
                        <Text type="secondary" style={{ fontSize: 13 }}>
                            Uploaded {dayjs(detail?.uploadedAt).fromNow()}
                        </Text>
                    </div>

                    <Space>
                        <Button
                            icon={<ReloadOutlined />}
                            onClick={() => { fetchDetail(); fetchQuestions(currentPage, statusFilter); }}
                        >
                            Refresh
                        </Button>
                        <Tooltip title="AI answer generation (Phase 5)">
                            <Button
                                type="primary"
                                icon={<RobotOutlined />}
                                disabled
                            >
                                Generate Answers
                            </Button>
                        </Tooltip>
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

                {/* Error */}
                {pageError && (
                    <Alert
                        type="error"
                        message={pageError}
                        showIcon
                        closable
                        style={{ marginBottom: 16 }}
                        onClose={() => setPageError('')}
                    />
                )}

                {/* Low confidence warning */}
                {detail?.lowConfidenceFlag && (
                    <Alert
                        type="warning"
                        showIcon
                        icon={<WarningOutlined />}
                        message="Low parse confidence"
                        description={detail.warningMessage}
                        style={{ marginBottom: 16 }}
                    />
                )}

                {/* Stats strip */}
                <Row gutter={12} style={{ marginBottom: 20 }}>
                    <Col span={4}>
                        <Card size="small">
                            <Statistic
                                title="Total"
                                value={detail?.totalQuestions || 0}
                                valueStyle={{ fontSize: 22 }}
                            />
                        </Card>
                    </Col>
                    <Col span={4}>
                        <Card size="small">
                            <Statistic
                                title="Pending"
                                value={pendingCount}
                                valueStyle={{ fontSize: 22, color: '#8c8c8c' }}
                            />
                        </Card>
                    </Col>
                    <Col span={4}>
                        <Card size="small">
                            <Statistic
                                title="AI Generated"
                                value={generatedCount}
                                valueStyle={{ fontSize: 22, color: '#1890ff' }}
                            />
                        </Card>
                    </Col>
                    <Col span={4}>
                        <Card size="small">
                            <Statistic
                                title="Approved"
                                value={approvedCount}
                                valueStyle={{ fontSize: 22, color: '#52c41a' }}
                            />
                        </Card>
                    </Col>
                    <Col span={4}>
                        <Card size="small">
                            <Statistic
                                title="Edited"
                                value={editedCount}
                                valueStyle={{ fontSize: 22, color: '#13c2c2' }}
                            />
                        </Card>
                    </Col>
                    <Col span={4}>
                        <Card size="small">
                            <Statistic
                                title="Rejected"
                                value={rejectedCount}
                                valueStyle={{ fontSize: 22, color: '#ff4d4f' }}
                            />
                        </Card>
                    </Col>
                </Row>

                {/* AI job status (visible once Phase 5 starts generating) */}
                {detail?.aiJob && detail.aiJob.status !== 'PENDING' && (
                    <Card size="small" style={{ marginBottom: 16 }}>
                        <Space>
                            <Text strong>AI Job:</Text>
                            <Tag color={
                                detail.aiJob.status === 'COMPLETED' ? 'success' :
                                    detail.aiJob.status === 'RUNNING'   ? 'processing' :
                                        detail.aiJob.status === 'FAILED'    ? 'error' : 'default'
                            }>
                                {detail.aiJob.status}
                            </Tag>
                            {detail.aiJob.status === 'RUNNING' && (
                                <Text type="secondary">
                                    {detail.aiJob.completedQuestions} / {detail.aiJob.totalQuestions} answered
                                </Text>
                            )}
                        </Space>
                    </Card>
                )}

                {/* Questions table */}
                <Card
                    title={
                        <Space>
                            <QuestionCircleOutlined />
                            <span>Questions</span>
                            <Badge count={totalQ} showZero style={{ backgroundColor: '#1890ff' }} overflowCount={9999} />
                        </Space>
                    }
                    extra={
                        <Select
                            value={statusFilter || 'ALL'}
                            style={{ width: 140 }}
                            size="small"
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
                            current:   currentPage + 1,  // Ant Design is 1-indexed, Spring is 0-indexed
                            pageSize:  pageSize,
                            total:     totalQ,
                            showSizeChanger: false,
                            showTotal: (total) => `${total} questions`,
                            onChange: (antPage) => setCurrentPage(antPage - 1),
                        }}
                        locale={{
                            emptyText: (
                                <div style={{ padding: 32, textAlign: 'center' }}>
                                    <QuestionCircleOutlined style={{ fontSize: 32, color: '#d9d9d9' }} />
                                    <p style={{ color: '#8c8c8c', marginTop: 8 }}>
                                        {statusFilter
                                            ? `No questions with status "${statusFilter}"`
                                            : 'No questions found'}
                                    </p>
                                </div>
                            ),
                        }}
                    />
                </Card>
            </div>
        </AppLayout>
    );
}