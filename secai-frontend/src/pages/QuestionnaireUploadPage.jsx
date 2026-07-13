// src/pages/QuestionnaireUploadPage.jsx
import { useState } from 'react';
import {
    Upload, Button, Progress, Alert, Typography,
    Card, Table, Tag, List, Steps,
} from 'antd';
import {
    InboxOutlined, FileExcelOutlined,
    CheckCircleOutlined, WarningOutlined,
} from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { uploadQuestionnaire } from '../api/questionnaires';
import AppLayout from '../components/AppLayout';

const { Dragger } = Upload;
const { Title, Text, Paragraph } = Typography;

const ALLOWED_EXTENSIONS = ['.xlsx', '.csv', '.docx'];
const FORMAT_COLOR = { XLSX: 'green', CSV: 'blue', DOCX: 'purple' };

const PREVIEW_COLUMNS = [
    {
        title: '#',
        dataIndex: 'questionNumber',
        width: 70,
        render: (v) => v || <Text type="secondary">—</Text>,
    },
    {
        title: 'Category',
        dataIndex: 'category',
        width: 160,
        ellipsis: true,
        render: (v) => v
            ? <Tag>{v}</Tag>
            : <Text type="secondary">—</Text>,
    },
    {
        title: 'Question',
        dataIndex: 'questionText',
        ellipsis: true,
    },
];

export default function QuestionnaireUploadPage() {
    const [fileList, setFileList]       = useState([]);
    const [uploading, setUploading]     = useState(false);
    const [uploadPct, setUploadPct]     = useState(0);
    const [result, setResult]           = useState(null);   // QuestionnaireUploadResponse
    const [uploadError, setUploadError] = useState('');
    const navigate = useNavigate();

    // ── Validation ───────────────────────────────────────────────────────
    const beforeUpload = (file) => {
        const name  = file.name.toLowerCase();
        const valid = ALLOWED_EXTENSIONS.some((ext) => name.endsWith(ext));
        if (!valid) {
            setUploadError(
                'Only XLSX, CSV, and DOCX files are supported. '
                + 'If you only have a PDF, ask the sender for the Excel or Word version.'
            );
            return Upload.LIST_IGNORE;
        }
        if (file.size > 20 * 1024 * 1024) {
            setUploadError('File must be smaller than 20MB.');
            return Upload.LIST_IGNORE;
        }
        setFileList([file]);
        setUploadError('');
        return false;
    };

    // ── Upload ───────────────────────────────────────────────────────────
    const handleUpload = async () => {
        if (!fileList.length) return;
        setUploading(true);
        setUploadPct(0);
        setResult(null);
        setUploadError('');

        try {
            const res = await uploadQuestionnaire(fileList[0], setUploadPct);
            setResult(res.data);
            setFileList([]);
        } catch (err) {
            setUploadError(
                err.response?.data?.error || 'Upload failed. Please check the file format and try again.'
            );
        } finally {
            setUploading(false);
        }
    };

    const handleReset = () => {
        setResult(null);
        setFileList([]);
        setUploadError('');
        setUploadPct(0);
    };

    // ── After-upload success view ─────────────────────────────────────────
    if (result) {
        const ext = result.filename?.split('.').pop()?.toUpperCase() || 'FILE';
        return (
            <AppLayout>
                <div style={{ maxWidth: 820, margin: '32px auto', padding: '0 24px' }}>
                    <Title level={4}>Questionnaire Parsed</Title>

                    {/* Low confidence warning */}
                    {result.lowConfidenceFlag && (
                        <Alert
                            type="warning"
                            showIcon
                            icon={<WarningOutlined />}
                            message="Low parse confidence"
                            description={result.warningMessage}
                            style={{ marginBottom: 16 }}
                        />
                    )}

                    {/* Success summary */}
                    {!result.lowConfidenceFlag && (
                        <Alert
                            type="success"
                            showIcon
                            icon={<CheckCircleOutlined />}
                            message={`${result.totalQuestions} questions extracted from "${result.filename}"`}
                            description="Review the preview below, then view the full list."
                            style={{ marginBottom: 16 }}
                        />
                    )}

                    {/* Stats */}
                    <Card style={{ marginBottom: 20 }}>
                        <div style={{ display: 'flex', gap: 40 }}>
                            <div>
                                <Text type="secondary">Questions found</Text>
                                <div style={{ fontSize: 28, fontWeight: 700, color: '#1890ff' }}>
                                    {result.totalQuestions}
                                </div>
                            </div>
                            <div>
                                <Text type="secondary">Format</Text>
                                <div style={{ marginTop: 4 }}>
                                    <Tag color={FORMAT_COLOR[ext] || 'default'} style={{ fontSize: 14 }}>
                                        {ext}
                                    </Tag>
                                </div>
                            </div>
                            <div>
                                <Text type="secondary">Status</Text>
                                <div style={{ marginTop: 4 }}>
                                    <Tag color="blue">Parsed — ready for AI</Tag>
                                </div>
                            </div>
                        </div>
                    </Card>

                    {/* Preview table */}
                    <Card
                        title={`Preview — first ${result.preview?.length} of ${result.totalQuestions} questions`}
                        style={{ marginBottom: 20 }}
                    >
                        <Table
                            dataSource={result.preview || []}
                            columns={PREVIEW_COLUMNS}
                            rowKey={(r, i) => i}
                            pagination={false}
                            size="small"
                        />
                        {result.totalQuestions > 10 && (
                            <div style={{ marginTop: 12, textAlign: 'center' }}>
                                <Text type="secondary">
                                    + {result.totalQuestions - (result.preview?.length || 0)} more questions
                                </Text>
                            </div>
                        )}
                    </Card>

                    {/* Actions */}
                    <div style={{ display: 'flex', gap: 10 }}>
                        <Button
                            type="primary"
                            onClick={() => navigate(`/questionnaires/${result.questionnaireId}`)}
                        >
                            View All Questions →
                        </Button>
                        <Button onClick={handleReset}>Upload Another</Button>
                        <Link to="/questionnaires">
                            <Button>All Questionnaires</Button>
                        </Link>
                    </div>
                </div>
            </AppLayout>
        );
    }

    // ── Upload form ───────────────────────────────────────────────────────
    return (
        <AppLayout>
            <div style={{ maxWidth: 680, margin: '32px auto', padding: '0 24px' }}>
                <Title level={4}>Upload Security Questionnaire</Title>
                <Text type="secondary" style={{ display: 'block', marginBottom: 24 }}>
                    Upload a customer questionnaire. The AI will answer it using your indexed documents.
                </Text>

                {uploadError && (
                    <Alert
                        type="error"
                        message={uploadError}
                        showIcon
                        closable
                        style={{ marginBottom: 16 }}
                        onClose={() => setUploadError('')}
                    />
                )}

                <Card style={{ marginBottom: 20 }}>
                    <Dragger
                        beforeUpload={beforeUpload}
                        fileList={fileList}
                        onRemove={() => setFileList([])}
                        accept=".xlsx,.csv,.docx"
                        maxCount={1}
                        disabled={uploading}
                    >
                        <p className="ant-upload-drag-icon">
                            <FileExcelOutlined style={{ color: '#52c41a' }} />
                        </p>
                        <p className="ant-upload-text">
                            Click or drag questionnaire file here
                        </p>
                        <p className="ant-upload-hint">
                            XLSX, CSV, or DOCX up to 20MB
                        </p>
                    </Dragger>

                    {uploading && (
                        <Progress
                            percent={uploadPct}
                            style={{ marginTop: 16 }}
                            status={uploadPct < 100 ? 'active' : 'success'}
                            format={(p) => `Uploading & parsing ${p}%`}
                        />
                    )}

                    <div style={{ marginTop: 16, display: 'flex', gap: 8 }}>
                        <Button
                            type="primary"
                            onClick={handleUpload}
                            disabled={!fileList.length || uploading}
                            loading={uploading}
                            icon={<FileExcelOutlined />}
                        >
                            {uploading ? 'Parsing...' : 'Upload & Parse'}
                        </Button>
                        <Link to="/questionnaires">
                            <Button disabled={uploading}>View All Questionnaires</Button>
                        </Link>
                    </div>
                </Card>

                {/* Format guide */}
                <Card title="Supported formats" style={{ marginBottom: 16 }}>
                    <List
                        size="small"
                        dataSource={[
                            {
                                type: 'XLSX',
                                color: 'green',
                                desc: 'Best format. Auto-detects #, Category, Question columns. Multi-sheet supported.',
                            },
                            {
                                type: 'CSV',
                                color: 'blue',
                                desc: 'Same column detection as XLSX. UTF-8 encoding recommended.',
                            },
                            {
                                type: 'DOCX',
                                color: 'purple',
                                desc: 'Extracts numbered lists (1., 1.1, Q1.) and Word list paragraphs.',
                            },
                        ]}
                        renderItem={(item) => (
                            <List.Item>
                                <Tag color={item.color} style={{ width: 52, textAlign: 'center' }}>{item.type}</Tag>
                                <Text type="secondary" style={{ marginLeft: 8 }}>{item.desc}</Text>
                            </List.Item>
                        )}
                    />
                </Card>

                {/* What happens next */}
                <Card title="What happens after upload?">
                    <Steps
                        direction="vertical"
                        size="small"
                        current={-1}
                        items={[
                            {
                                title: 'Parse',
                                description: 'Questions extracted from your file. Preview shown immediately.',
                            },
                            {
                                title: 'AI Generation (Phase 5)',
                                description: 'Click "Generate Answers" to let the AI answer using your documents.',
                            },
                            {
                                title: 'Review & Edit',
                                description: 'Approve, edit, or reject each answer before exporting.',
                            },
                            {
                                title: 'Export',
                                description: 'Download completed questionnaire as Excel to send to the customer.',
                            },
                        ]}
                    />
                </Card>
            </div>
        </AppLayout>
    );
}