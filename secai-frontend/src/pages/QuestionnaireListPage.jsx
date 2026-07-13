// src/pages/QuestionnaireListPage.jsx  — NEW FILE
import { useEffect, useState, useCallback } from 'react';
import {
    Table, Tag, Button, Popconfirm, Typography,
    Alert, Space, Tooltip,
} from 'antd';
import {
    PlusOutlined, DeleteOutlined, ReloadOutlined,
    WarningOutlined, FileExcelOutlined,
} from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { listQuestionnaires, deleteQuestionnaire } from '../api/questionnaires';
import AppLayout from '../components/AppLayout';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';

dayjs.extend(relativeTime);

const { Title, Text } = Typography;

const FORMAT_COLOR  = { XLSX: 'green', CSV: 'blue', DOCX: 'purple' };
const STATUS_COLOR  = {
    UPLOADED:   'default',
    PARSED:     'blue',
    GENERATING: 'processing',
    COMPLETED:  'success',
    FAILED:     'error',
};

export default function QuestionnaireListPage() {
    const [items, setItems]   = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError]   = useState('');
    const navigate = useNavigate();

    const fetchList = useCallback(async () => {
        setLoading(true);
        setError('');
        try {
            const res = await listQuestionnaires();
            setItems(res.data);
        } catch {
            setError('Failed to load questionnaires.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { fetchList(); }, [fetchList]);

    const handleDelete = async (id, e) => {
        e.stopPropagation();
        try {
            await deleteQuestionnaire(id);
            setItems((prev) => prev.filter((q) => q.id !== id));
        } catch {
            setError('Failed to delete questionnaire.');
        }
    };

    const columns = [
        {
            title: 'Filename',
            dataIndex: 'filename',
            render: (name, record) => (
                <Space>
                    <FileExcelOutlined style={{ color: '#52c41a' }} />
                    <Text strong>{name}</Text>
                    {record.lowConfidenceFlag && (
                        <Tooltip title="Low parse confidence — some questions may be missing">
                            <WarningOutlined style={{ color: '#faad14' }} />
                        </Tooltip>
                    )}
                </Space>
            ),
        },
        {
            title: 'Format',
            dataIndex: 'originalFormat',
            width: 90,
            render: (f) => <Tag color={FORMAT_COLOR[f] || 'default'}>{f}</Tag>,
        },
        {
            title: 'Questions',
            dataIndex: 'totalQuestions',
            width: 110,
            render: (n) => <Text strong>{n}</Text>,
        },
        {
            title: 'Status',
            dataIndex: 'status',
            width: 120,
            render: (s) => <Tag color={STATUS_COLOR[s] || 'default'}>{s}</Tag>,
        },
        {
            title: 'Uploaded',
            dataIndex: 'uploadedAt',
            width: 150,
            render: (v) => (
                <Tooltip title={dayjs(v).format('MMM D YYYY, HH:mm')}>
                    <Text type="secondary">{dayjs(v).fromNow()}</Text>
                </Tooltip>
            ),
        },
        {
            title: '',
            width: 56,
            render: (_, record) => (
                <Popconfirm
                    title="Delete this questionnaire?"
                    description="All questions will be permanently deleted."
                    onConfirm={(e) => handleDelete(record.id, e)}
                    onCancel={(e) => e?.stopPropagation()}
                    okText="Delete"
                    okType="danger"
                    placement="topRight"
                >
                    <Button
                        type="text" danger
                        icon={<DeleteOutlined />}
                        size="small"
                        onClick={(e) => e.stopPropagation()}
                    />
                </Popconfirm>
            ),
        },
    ];

    return (
        <AppLayout>
            <div style={{ padding: 24 }}>
                <div style={{
                    display: 'flex', justifyContent: 'space-between',
                    alignItems: 'center', marginBottom: 16,
                }}>
                    <Title level={4} style={{ margin: 0 }}>Questionnaires</Title>
                    <Space>
                        <Button icon={<ReloadOutlined />} onClick={fetchList} loading={loading}>
                            Refresh
                        </Button>
                        <Link to="/questionnaires/upload">
                            <Button type="primary" icon={<PlusOutlined />}>
                                Upload Questionnaire
                            </Button>
                        </Link>
                    </Space>
                </div>

                {error && (
                    <Alert type="error" message={error} showIcon closable
                           style={{ marginBottom: 16 }} onClose={() => setError('')} />
                )}

                <Table
                    dataSource={items}
                    columns={columns}
                    rowKey="id"
                    loading={loading}
                    pagination={{ pageSize: 20, hideOnSinglePage: true }}
                    onRow={(record) => ({
                        onClick: () => navigate(`/questionnaires/${record.id}`),
                        style: { cursor: 'pointer' },
                    })}
                    locale={{
                        emptyText: (
                            <div style={{ padding: 48, textAlign: 'center' }}>
                                <FileExcelOutlined style={{ fontSize: 40, color: '#d9d9d9', marginBottom: 12 }} />
                                <p style={{ color: '#8c8c8c', marginBottom: 16 }}>
                                    No questionnaires yet. Upload a customer questionnaire to get started.
                                </p>
                                <Link to="/questionnaires/upload">
                                    <Button type="primary" icon={<PlusOutlined />}>
                                        Upload Questionnaire
                                    </Button>
                                </Link>
                            </div>
                        ),
                    }}
                />
            </div>
        </AppLayout>
    );
}