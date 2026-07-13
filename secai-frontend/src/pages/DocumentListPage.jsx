// src/pages/DocumentListPage.jsx
import { useEffect, useState, useCallback } from 'react';
import {
    Table, Tag, Button, Popconfirm, Typography,
    Alert, Space, Tooltip, Badge,
} from 'antd';
import {
    UploadOutlined, DeleteOutlined, ReloadOutlined,
    CheckCircleOutlined, ExclamationCircleOutlined,
    LoadingOutlined, FileTextOutlined,
} from '@ant-design/icons';

import AppLayout from '../components/AppLayout';

import { Link, useNavigate } from 'react-router-dom';
import { listDocuments, deleteDocument } from '../api/documents';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';

dayjs.extend(relativeTime);

const { Title, Text } = Typography;

// Status → Ant Design tag color
const STATUS_COLOR = {
    PENDING:    'default',
    PROCESSING: 'processing',
    READY:      'success',
    FAILED:     'error',
};

// Status → icon shown in tag
const STATUS_ICON = {
    PENDING:    null,
    PROCESSING: <LoadingOutlined />,
    READY:      <CheckCircleOutlined />,
    FAILED:     <ExclamationCircleOutlined />,
};

const POLL_INTERVAL_MS = 5000;

export default function DocumentListPage() {
    const [documents, setDocuments]   = useState([]);
    const [loading, setLoading]       = useState(true);
    const [error, setError]           = useState('');
    const navigate = useNavigate();

    const fetchDocuments = useCallback(async (showLoader = true) => {
        if (showLoader) setLoading(true);
        setError('');
        try {
            const res = await listDocuments();
            setDocuments(res.data);
        } catch {
            setError('Failed to load documents. Please refresh.');
        } finally {
            setLoading(false);
        }
    }, []);

    // Initial load
    useEffect(() => { fetchDocuments(); }, [fetchDocuments]);

    // Smart polling: only runs while documents are in transient states
    useEffect(() => {
        const hasInFlight = documents.some(
            (d) => d.status === 'PENDING' || d.status === 'PROCESSING'
        );
        if (!hasInFlight) return;

        const interval = setInterval(() => fetchDocuments(false), POLL_INTERVAL_MS);
        return () => clearInterval(interval);
    }, [documents, fetchDocuments]);

    const handleDelete = async (id, e) => {
        e.stopPropagation(); // prevent row click navigating
        try {
            await deleteDocument(id);
            setDocuments((prev) => prev.filter((d) => d.id !== id));
        } catch {
            setError('Failed to delete document. Please try again.');
        }
    };

    const columns = [
        {
            title: 'Document',
            dataIndex: 'filename',
            render: (name, record) => (
                <Space>
                    <FileTextOutlined style={{ color: '#1890ff' }} />
                    <Text strong>{name}</Text>
                    {record.status === 'PROCESSING' && (
                        <Text type="secondary" style={{ fontSize: 12 }}>
                            — AI is indexing...
                        </Text>
                    )}
                </Space>
            ),
        },
        {
            title: 'Status',
            dataIndex: 'status',
            width: 150,
            render: (status, record) => {
                const tag = (
                    <Tag
                        color={STATUS_COLOR[status] || 'default'}
                        icon={STATUS_ICON[status]}
                    >
                        {status}
                    </Tag>
                );

                if (status === 'FAILED' && record.errorMessage) {
                    return (
                        <Tooltip title={record.errorMessage} color="red">
                            {tag}
                        </Tooltip>
                    );
                }
                return tag;
            },
        },
        {
            title: 'Chunks Indexed',
            dataIndex: 'chunkCount',
            width: 140,
            render: (count, record) => {
                if (record.status === 'READY' && count != null) {
                    return (
                        <Badge
                            count={count}
                            showZero
                            style={{ backgroundColor: '#52c41a' }}
                            overflowCount={9999}
                        />
                    );
                }
                if (record.status === 'PROCESSING') {
                    return <Text type="secondary">Indexing…</Text>;
                }
                if (record.status === 'FAILED') {
                    return <Text type="danger">—</Text>;
                }
                return <Text type="secondary">Pending</Text>;
            },
        },
        {
            title: 'Uploaded',
            dataIndex: 'uploadedAt',
            width: 160,
            render: (val) => (
                <Tooltip title={dayjs(val).format('MMM D YYYY, HH:mm:ss')}>
                    <Text type="secondary">{dayjs(val).fromNow()}</Text>
                </Tooltip>
            ),
        },
        {
            title: 'Processed',
            dataIndex: 'processedAt',
            width: 160,
            render: (val, record) => {
                if (!val) return <Text type="secondary">—</Text>;
                return (
                    <Tooltip title={dayjs(val).format('MMM D YYYY, HH:mm:ss')}>
                        <Text type="secondary">{dayjs(val).fromNow()}</Text>
                    </Tooltip>
                );
            },
        },
        {
            title: '',
            width: 60,
            render: (_, record) => (
                <Popconfirm
                    title="Delete this document?"
                    description="All indexed chunks will also be deleted. This cannot be undone."
                    onConfirm={(e) => handleDelete(record.id, e)}
                    onCancel={(e) => e?.stopPropagation()}
                    okText="Delete"
                    okType="danger"
                    placement="topRight"
                >
                    <Button
                        type="text"
                        danger
                        icon={<DeleteOutlined />}
                        size="small"
                        onClick={(e) => e.stopPropagation()}
                    />
                </Popconfirm>
            ),
        },
    ];

    // Summary stats banner
    const stats = documents.reduce(
        (acc, d) => {
            acc[d.status] = (acc[d.status] || 0) + 1;
            return acc;
        },
        {}
    );

    return (
        <AppLayout>
        <div style={{ padding: 24 }}>

            {/* Header */}
            <div style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginBottom: 16,
            }}>
                <Title level={4} style={{ margin: 0 }}>Security Documents</Title>
                <Space>
                    <Button
                        icon={<ReloadOutlined />}
                        onClick={() => fetchDocuments()}
                        loading={loading}
                    >
                        Refresh
                    </Button>
                    <Link to="/documents/upload">
                        <Button type="primary" icon={<UploadOutlined />}>
                            Upload Document
                        </Button>
                    </Link>
                </Space>
            </div>

            {/* Error banner */}
            {error && (
                <Alert
                    type="error"
                    message={error}
                    style={{ marginBottom: 16 }}
                    showIcon
                    closable
                    onClose={() => setError('')}
                />
            )}

            {/* Stats strip — only show once there are documents */}
            {documents.length > 0 && (
                <div style={{
                    display: 'flex',
                    gap: 24,
                    marginBottom: 16,
                    padding: '10px 16px',
                    background: '#fafafa',
                    borderRadius: 6,
                    border: '1px solid #f0f0f0',
                }}>
                    {stats.READY      && <Text><CheckCircleOutlined style={{ color: '#52c41a' }} /> {stats.READY} Ready</Text>}
                    {stats.PROCESSING && <Text><LoadingOutlined style={{ color: '#1890ff' }} /> {stats.PROCESSING} Processing</Text>}
                    {stats.PENDING    && <Text style={{ color: '#8c8c8c' }}>{stats.PENDING} Pending</Text>}
                    {stats.FAILED     && <Text type="danger"><ExclamationCircleOutlined /> {stats.FAILED} Failed</Text>}
                </div>
            )}

            {/* Table */}
            <Table
                dataSource={documents}
                columns={columns}
                rowKey="id"
                loading={loading}
                pagination={{ pageSize: 20, hideOnSinglePage: true }}
                onRow={(record) => ({
                    onClick: () => navigate(`/documents/${record.id}`),
                    style: { cursor: 'pointer' },
                })}
                rowClassName={(record) =>
                    record.status === 'FAILED' ? 'doc-row-failed' : ''
                }
                locale={{
                    emptyText: (
                        <div style={{ padding: 48, textAlign: 'center' }}>
                            <FileTextOutlined style={{ fontSize: 40, color: '#d9d9d9', marginBottom: 12 }} />
                            <p style={{ color: '#8c8c8c', marginBottom: 16 }}>
                                No documents yet. Upload your security policies and reports to get started.
                            </p>
                            <Link to="/documents/upload">
                                <Button type="primary" icon={<UploadOutlined />}>
                                    Upload your first document
                                </Button>
                            </Link>
                        </div>
                    ),
                }}
            />

            {/* Inline CSS for failed row highlight */}
            <style>{`
        .doc-row-failed td { background-color: #fff2f0 !important; }
        .doc-row-failed:hover td { background-color: #ffebe8 !important; }
      `}</style>
        </div>
        </AppLayout>
    );
}